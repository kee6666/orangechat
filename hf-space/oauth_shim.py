"""OAuth consent shim for Nocturne Memory MCP on Hugging Face Spaces."""
import os, secrets, time
import httpx
from starlette.applications import Starlette
from starlette.requests import Request
from starlette.responses import JSONResponse, RedirectResponse, HTMLResponse, Response
from starlette.routing import Route

INTERNAL_TOKEN = os.environ["API_TOKEN"]
PASSCODE = os.environ["PASSCODE"]
BASE_URL = os.environ["BASE_URL"].rstrip("/")

_codes = {}

WELL_KNOWN_AUTH = {
    "issuer": BASE_URL,
    "authorization_endpoint": f"{BASE_URL}/authorize",
    "token_endpoint": f"{BASE_URL}/token",
    "registration_endpoint": f"{BASE_URL}/register",
    "response_types_supported": ["code"],
    "grant_types_supported": ["authorization_code"],
    "code_challenge_methods_supported": ["S256"],
    "token_endpoint_auth_methods_supported": ["none"],
}

WELL_KNOWN_RESOURCE = {
    "resource": f"{BASE_URL}/mcp",
    "authorization_servers": [BASE_URL],
}

PAGE = """<!doctype html><html><head><meta charset=utf-8><meta name=viewport content="width=device-width,initial-scale=1">
<title>Memory Gate</title><style>
body{font-family:system-ui;background:#14101c;color:#e8e0f0;display:flex;min-height:100vh;align-items:center;justify-content:center;margin:0}
.card{background:#1e1830;padding:2rem;border-radius:16px;width:min(90vw,360px);box-shadow:0 8px 40px rgba(0,0,0,.4)}
h1{font-size:1.2rem;margin:0 0 .5rem}p{color:#9a8fb8;font-size:.85rem;line-height:1.5;margin:0 0 1.2rem}
input{width:100%;box-sizing:border-box;padding:.7rem;border-radius:10px;border:1px solid #3d3158;background:#14101c;color:#fff;font-size:1rem}
button{width:100%;margin-top:.8rem;padding:.7rem;border-radius:10px;border:0;background:#7c5cff;color:#fff;font-size:1rem}
.err{color:#ff7b8a;font-size:.8rem;margin-top:.6rem;min-height:1em}
</style></head><body><div class=card>
<h1>🌙 记忆之门</h1>
<p>这里存放着一段独立的记忆。输入口令，放行这位访客。</p>
<form method=post action=/authorize>
<input type=hidden name=state value="__STATE__">
<input type=hidden name=redirect_uri value="__REDIRECT__">
<input type=hidden name=code_challenge value="__CHALLENGE__">
<input name=passcode placeholder=口令 autofocus>
<button>开门</button><div class=err>__ERR__</div>
</form></div></body></html>"""

def _page(state="", redirect="", challenge="", err=""):
    return PAGE.replace("__STATE__", state).replace("__REDIRECT__", redirect).replace("__CHALLENGE__", challenge).replace("__ERR__", err)

async def metadata(request):
    return JSONResponse(WELL_KNOWN_AUTH)

async def protected_resource(request):
    return JSONResponse(WELL_KNOWN_RESOURCE)

async def register(request):
    try:
        body = await request.json()
    except Exception:
        body = {}
    return JSONResponse({
        "client_id": body.get("client_id", "claude-client"),
        "client_secret": "",
        "redirect_uris": body.get("redirect_uris", ["https://claude.ai/api/mcp/auth/callback"]),
        "grant_types": ["authorization_code"],
        "response_types": ["code"],
        "token_endpoint_auth_method": "none",
    })

async def authorize_get(request):
    qp = request.query_params
    redirect_uri = qp.get("redirect_uri", "")
    if not redirect_uri:
        return HTMLResponse(_page(err="missing redirect_uri"), status_code=400)
    return HTMLResponse(_page(
        state=qp.get("state", ""),
        redirect=redirect_uri,
        challenge=qp.get("code_challenge", ""),
    ))

async def authorize_post(request):
    form = await request.form()
    if form.get("passcode", "") != PASSCODE:
        return HTMLResponse(_page(
            state=form.get("state", ""), redirect=form.get("redirect_uri", ""),
            challenge=form.get("code_challenge", ""), err="口令不对，再想想。"), status_code=401)
    code = secrets.token_urlsafe(24)
    _codes[code] = time.time() + 120
    redirect_uri = form.get("redirect_uri") or "https://claude.ai/api/mcp/auth/callback"
    sep = "&" if "?" in redirect_uri else "?"
    return RedirectResponse(f"{redirect_uri}{sep}code={code}&state={form.get('state','')}", status_code=302)

async def token(request):
    form = await request.form()
    code = form.get("code", "")
    exp = _codes.pop(code, 0)
    if not code or not exp or exp < time.time():
        return JSONResponse({"error": "invalid_grant"}, status_code=400)
    return JSONResponse({"access_token": INTERNAL_TOKEN, "token_type": "bearer", "expires_in": 31536000})

async def health(request):
    return JSONResponse({"ok": True})

HOP = {"connection", "keep-alive", "transfer-encoding", "te", "trailer", "upgrade", "proxy-authorization", "host", "content-length"}

async def proxy(request):
    async with httpx.AsyncClient(timeout=180) as client:
        body = await request.body()
        headers = {k: v for k, v in request.headers.items() if k.lower() not in HOP}
        url = "http://127.0.0.1:8233" + request.url.path
        if request.url.query:
            url += "?" + request.url.query
        try:
            resp = await client.request(request.method, url, content=body, headers=headers)
        except httpx.HTTPError as e:
            return JSONResponse({"error": f"upstream: {e}"}, status_code=502)
        rh = {k: v for k, v in resp.headers.items() if k.lower() not in HOP | {"content-encoding", "content-length"}}
        return Response(resp.content, status_code=resp.status_code, headers=rh)

routes = [
    Route("/.well-known/oauth-authorization-server", metadata),
    Route("/.well-known/oauth-protected-resource", protected_resource),
    Route("/register", register, methods=["POST"]),
    Route("/authorize", authorize_get, methods=["GET"]),
    Route("/authorize", authorize_post, methods=["POST"]),
    Route("/token", token, methods=["POST"]),
    Route("/health", health),
    Route("/{rest:path}", proxy, methods=["GET", "POST", "DELETE", "PUT", "OPTIONS"]),
]

app = Starlette(routes=routes)
