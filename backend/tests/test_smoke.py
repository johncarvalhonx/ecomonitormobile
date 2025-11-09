from fastapi.testclient import TestClient
from app.main import app
def test_status():
    r = TestClient(app).get('/status')
    assert r.status_code == 200
    assert r.json().get('ok') is True
