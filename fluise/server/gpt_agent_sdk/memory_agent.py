class MemoryAgent:
    def __init__(self, client):
        self.client = client
        self.raw_history = []        # 전체 대화 기록
        self.summary_log = []       # 요약 기록 (요약의 요약 포함)
        self.pending = []           # 아직 요약되지 않은 대화쌍
        self.dialog_log = []        # 전체 로그용 텍스트
        self.recent_keep = 3        # 최근 대화 유지 개수

    def add(self, user, assistant):
        pair = {"user": user, "assistant": assistant}
        self.raw_history.append(pair)
        self.pending.append(pair)
        self.dialog_log.append(f"User: {user}\nAssistant: {assistant}")

        # 대화 요약 (3쌍마다)
        if len(self.pending) >= 3:
            summary = self.summarize_pending()
            self.summary_log.append(summary)
            self.pending = []

        # 요약의 요약 (요약도 3개 이상일 때)
        if len(self.summary_log) >= 3:
            self.summarize_summary_log()

    def summarize_pending(self):
        print("🧠 [MemoryAgent] summarize_pending() 호출됨")
        prompt = "다음은 사용자와 어시스턴트의 대화입니다. 사용자의 발화를 중심으로 요약해 주세요:\n"
        for pair in self.pending:
            prompt += f"User: {pair['user']}\nAssistant: {pair['assistant']}\n"

        print("📤 요약 프롬프트:\n", prompt)
        response = self.client.chat.completions.create(
            model="gpt-4o-mini",
            messages=[{"role": "user", "content": prompt}]
        )
        summary = response.choices[0].message.content.strip()
        print("📥 요약 결과:\n", summary)
        return summary

    def summarize_summary_log(self):
        print("🧠 요약의 요약 시작")
        prompt = "다음은 이전의 대화 요약 목록입니다. 전체적으로 핵심만 간결하게 요약해 주세요:\n"
        for s in self.summary_log:
            prompt += f"- {s}\n"

        response = self.client.chat.completions.create(
            model="gpt-4o-mini",
            messages=[{"role": "user", "content": prompt}]
        )
        summary = response.choices[0].message.content.strip()
        print("📉 요약의 요약 결과:\n", summary)

        self.summary_log = [summary]   # ✅ 메모리에서 덮어쓰기
        self.save_summaries()          # ✅ 파일에도 반영

    def get_summary_prompt(self):
        summary_part = "\n".join(self.summary_log)
        recent_part = "\n".join([
            f"User: {p['user']}\nAssistant: {p['assistant']}"
            for p in self.raw_history[-self.recent_keep:]
        ])
        return f"[기억 요약]\n{summary_part.strip()}\n\n[최근 대화]\n{recent_part.strip()}"

    def get_dialog_log(self):
        return "[전체 대화 내용]\n" + "\n\n".join(self.dialog_log)

