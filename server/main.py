from fastapi import FastAPI, Request
from fastapi.responses import StreamingResponse
from fastapi.middleware.cors import CORSMiddleware
from pydantic import BaseModel
import os
from dotenv import load_dotenv
from gpt_agent_sdk import GPTAgent

load_dotenv()

app = FastAPI()

app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_methods=["*"],
    allow_headers=["*"],
)

agent = GPTAgent(api_key=os.getenv("OPENAI_API_KEY"))

class AppRegistration(BaseModel):
    device_id: str
    accessible_apps: dict      # 사용자 실행 가능
    inaccessible_apps: dict    # 실행 불가(시스템·숨김)

@app.post("/register_apps")
async def register_apps(data: AppRegistration):
    agent.tool.update_app_keywords(data.device_id, data.accessible_apps)
    agent.tool.update_inaccessible_apps(data.device_id, data.inaccessible_apps)
    return {"status": "ok"}

@app.post("/chat")
async def chat(request: Request):
    body = await request.json()
    user_msg = body.get("message", "")
    device_id = body.get("device_id", "default")

    reply, is_chat = agent.process(user_msg, device_id)

    def stream_reply():
        clean_reply = ' '.join(reply.strip().splitlines())
        yield f"data: {clean_reply}\n\n"

    return StreamingResponse(stream_reply(), media_type="text/event-stream")

@app.get("/next_cmds/{device_id}")
async def next_cmds(device_id: str):
    """단말이 주기적으로 호출해서 대기 중인 명령을 가져감"""
    return {"commands": agent.tool.pop_cmds(device_id)}

