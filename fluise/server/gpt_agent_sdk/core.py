from openai import OpenAI
import json
import os
from datetime import datetime

from .tool_agent import ToolAgent
from .mobile_agent import MobileAgent

SUMMARY_FILE = "summaries.json"

class MemoryAgent:
    def __init__(self, client: OpenAI):
        self.client = client
        self.raw_history = []
        self.summary_log = []
        self.pending = []
        self.load_summaries()

    def add(self, user, assistant):
        pair = {"user": user, "assistant": assistant}
        self.raw_history.append(pair)
        self.pending.append(pair)

        if len(self.pending) >= 3:
            summary = self.summarize_pending()
            self.pending = []

            self.summary_log.append(summary)

            if len(self.summary_log) >= 3:
                self.summarize_summary_log()
            else:
                self.save_summaries()

    def summarize_pending(self):
        print("요약 기능")

        prompt = "다음은 사용자와 어시스턴트의 대화입니다. 사용자의 발화를 중심으로 요약해 주세요:\n"
        for pair in self.pending:
            prompt += f"User: {pair['user']}\nAssistant: {pair['assistant']}\n"

        response = self.client.chat.completions.create(
            model="gpt-4o",
            messages=[{"role": "user", "content": prompt}]
        )
        result = response.choices[0].message.content.strip()

        print("요약 결과:\n", result)
        return result

    def summarize_summary_log(self):
        print("요약의 요약 시작")
        prompt = "다음은 대화 요약 목록입니다. 전체 내용을 핵심적으로 다시 요약해 주세요:\n"
        for summary in self.summary_log:
            prompt += f"- {summary}\n"

        response = self.client.chat.completions.create(
            model="gpt-4o",
            messages=[{"role": "user", "content": prompt}]
        )
        result = response.choices[0].message.content.strip()
        print("요약의 요약 결과:\n", result)

        self.summary_log = [result]  # 요약의 요약으로 교체
        self.save_summaries()

    def get_summary_prompt(self):
        combined = "\n".join(self.summary_log)
        for pair in self.pending:
            combined += f"\nUser: {pair['user']}\nAssistant: {pair['assistant']}"
        return f"[기억된 내용 요약]\n{combined.strip()}\n"

    def save_summaries(self):
        try:
            with open(SUMMARY_FILE, "w", encoding="utf-8") as f:
                json.dump(self.summary_log, f, ensure_ascii=False, indent=2)
        except Exception as e:
            print("⚠️ 요약 저장 실패:", e)

    def load_summaries(self):
        if os.path.exists(SUMMARY_FILE):
            try:
                with open(SUMMARY_FILE, "r", encoding="utf-8") as f:
                    self.summary_log = json.load(f)
                print("✅ 요약 불러오기 성공")
            except Exception as e:
                print("⚠️ 요약 불러오기 실패:", e)
        else:
            print("ℹ️ 요약 파일 없음, 새로 시작함")


class GPTAgent:
    def __init__(self, api_key: str):
        self.client = OpenAI(api_key=api_key)
        self.memory = MemoryAgent(self.client)
        self.tool = ToolAgent()
        self.mobile_agent = MobileAgent(self.tool)

    def build_prompt(self, user_msg: str) -> str:
        return self.memory.get_summary_prompt() + f"\nUser: {user_msg}"

    def process(self, user_msg: str, device_id: str):
        tool_decision_prompt = f"""
다음은 사용자의 발화입니다. 이 발화가 도구 실행을 요구하는 경우, JSON 형식으로 응답하세요.

필드 설명:
- type: "tool_call" 또는 "chat"
- tool: 실행할 도구 이름 (예: "get_time", "open_app", "control_app")
- target: (선택) 실행 대상 예: 앱 이름, 설정 명령 등

예시 1 (시간 확인 요청):
{{
  "type": "tool_call",
  "tool": "get_time"
}}

예시 2 (앱 실행 요청):
{{
  "type": "tool_call",
  "tool": "open_app",
  "target": "카카오톡"
}}

예시 3 (단순 대화):
{{
  "type": "chat"
}}

사용자 발화:
\"{user_msg}\"
"""

        response = self.client.chat.completions.create(
            model="gpt-4o-mini",
            messages=[{"role": "user", "content": tool_decision_prompt}]
        )
        raw = response.choices[0].message.content.strip()

        try:
            parsed = json.loads(raw)
            log_msg = f"📦 GPT 판단 결과: type={parsed.get('type')}"
            if parsed.get("tool"):
                log_msg += f" tool={parsed['tool']}"
            if parsed.get("target"):
                log_msg += f" target={parsed['target']}"
            print(log_msg)
        except json.JSONDecodeError:
            print("📦 GPT 판단 결과: (JSON 파싱 실패)")
            parsed = {"type": "chat"}

        if parsed["type"] == "tool_call":
            tool = parsed.get("tool")
            target = parsed.get("target", "")

            if tool in ["open_app", "temp_screen"]:
                reply = self.mobile_agent.handle(tool, target, device_id)
            else:
                reply = self._handle_tool(tool, target, device_id, user_msg)

            self.memory.add(user_msg, reply)
            return reply, False

        # 일반 대화
        prompt = self.build_prompt(user_msg)
        response = self.client.chat.completions.create(
            model="gpt-4o-mini",
            messages=[
                {"role": "system", "content": "사용자의 대화에 자연스럽게 응답해."},
                {"role": "user", "content": prompt}
            ]
        )
        reply = response.choices[0].message.content.strip()
        self.memory.add(user_msg, reply)
        return reply, True

    def _handle_tool(self, tool: str, target: str, device_id: str, user_msg: str) -> str:
        if tool == "get_time":
            return self.tool.get_current_time_message()

        elif tool == "open_app":
            package = self.tool.find_app_package(device_id, target)
            if package:
                return f"[TOOL_CALL] open_app:{package}"
            else:
                return "[TOOL_CALL] unknown_app"

        elif tool == "control_app":
            # 추후 제어 로직을 여기에 확장
            return f"[TOOL_CALL] control_app:{target}"

        else:
            return f"[TOOL_CALL] unknown_tool:{tool}"

