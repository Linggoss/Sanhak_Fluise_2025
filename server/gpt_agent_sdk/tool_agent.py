from collections import defaultdict

class ToolAgent:
    def __init__(self):
        self.app_keywords = {}          # 접근 가능 앱
        self.inaccessible = {}          # 접근 불가 앱   ← 새 필드
        self.pending_cmds = defaultdict(list)

    def update_app_keywords(self, device_id: str, apps: dict):
        self.app_keywords[device_id] = apps
        print(f"[📲] {device_id} 앱 리스트 등록됨: {apps}")

    def find_app_package(self, device_id: str, app_name: str) -> str | None:
        if device_id not in self.app_keywords:
            return None
        for name, package in self.app_keywords[device_id].items():
            if app_name in name:
                return package
        return None

    def get_current_time_message(self) -> str:
        from datetime import datetime
        now = datetime.now().strftime("현재 시간은 %Y년 %m월 %d일 %H시 %M분 %S초입니다.")
        return now

    def push_cmd(self, device_id: str, cmd: dict):
        """단말이 가져갈 명령을 큐에 적재"""
        self.pending_cmds[device_id].append(cmd)

    def pop_cmds(self, device_id: str) -> list[dict]:
        """단말이 한 번에 가져가고 큐는 비움"""
        cmds = self.pending_cmds.get(device_id, [])
        self.pending_cmds[device_id] = []
        return cmds

    def update_inaccessible_apps(self, device_id: str, apps: dict):
        self.inaccessible[device_id] = apps
        print(f"[🔒] {device_id} 접근 불가 앱: {len(apps)}개")
