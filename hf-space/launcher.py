import os, sys, threading, time
import uvicorn
from oauth_shim import app as shim_app

def run_nocturne():
    os.environ.setdefault("_NOCTURNE_SSE_MODE", "1")
    sys.path.insert(0, "/app")
    import run_sse
    run_sse.main()

def wait_upstream():
    import httpx
    for _ in range(120):
        try:
            httpx.get("http://127.0.0.1:8233/health", timeout=2)
            return True
        except Exception:
            time.sleep(1)
    return False

if __name__ == "__main__":
    t = threading.Thread(target=run_nocturne, daemon=True)
    t.start()
    wait_upstream()
    print("[launcher] nocturne up, starting oauth shim on 7860", flush=True)
    uvicorn.run(shim_app, host="0.0.0.0", port=int(os.environ.get("PORT", "7860")))
