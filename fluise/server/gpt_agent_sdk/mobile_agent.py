# gpt_agent_sdk/mobile_agent.py

class MobileAgent:
    def __init__(self, tool_agent):
        self.tool = tool_agent

    def handle(self, intent: str, target: str | None, device_id: str):
        if intent == "open_app":
            pkg = self.tool.find_app_package(device_id, target)
            if pkg:
                # → 서버 큐에 명령 적재
                self.tool.push_cmd(device_id, {"type": "open_app", "pkg": pkg})
                return f"“{target}” 앱을 실행하도록 지시했어요."
            else :
                return "앱을 찾지 못했어요. 다른 이름인지 확인해 주세요."

        elif intent == "temp_screen":
            # 임시 화면 조작 예시
            return f"[TOOL_CALL] tap:{target or 'default'}"

        else:
            return f"[TOOL_CALL] unknown_mobile_intent:{intent}"

