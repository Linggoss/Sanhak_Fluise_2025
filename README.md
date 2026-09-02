# Fluise (플루이즈)

GPT 기반 자연어 명령으로 모바일 앱을 실행·제어하는 안드로이드 에이전트 시스템.

2025년도 과학기술정보통신부·정보통신기획평가원 'SW중심대학사업' 산학협력프로젝트로 4개월간(2025.4 ~ 2025.8) 진행했다. 사용자가 앱에 자연어로 말을 걸면 서버의 GPT 에이전트가 발화 의도를 분석해 일반 대화로 응답하거나, 안드로이드 단말에 설치된 앱을 찾아 실행 명령을 내려준다.

> 본 저장소는 팀 프로젝트 중 개인이 담당한 서버(FastAPI 에이전트)와 안드로이드 클라이언트 구현 부분을 정리한 것이다. 개발 중 사용한 API 키·개인 테스트 데이터는 모두 제거했다.

## 아키텍처

```
[Android App]  <--HTTP(SSE)-->  [FastAPI Server]  <-->  [OpenAI API]
   ChatScreen                     gpt_agent_sdk
   CommandService (Foreground)      ├─ GPTAgent   : 의도 판단(tool_call vs chat) 오케스트레이션
   PackageChangeReceiver            ├─ MemoryAgent: 대화 요약·기억(3턴마다 요약, 요약의 요약)
   AgentClient                      ├─ ToolAgent  : 앱 목록/실행 명령 큐 관리
                                     └─ MobileAgent: 앱 실행 등 모바일 제어 인텐트 처리
```

**동작 흐름**
1. 앱 실행 시 설치된 앱 목록을 서버에 등록 (`POST /register_apps`)
2. 사용자가 메시지를 보내면 서버가 GPT로 발화 의도를 판단해 `chat` 또는 `tool_call`로 분기 (`POST /chat`, SSE 스트리밍 응답)
3. 앱 실행이 필요하면 실행 명령을 큐에 적재하고, 안드로이드가 5초 주기로 폴링해 가져와 실행 (`GET /next_cmds/{device_id}`)

## 기술 스택

| 영역 | 기술 |
|---|---|
| 서버 | Python, FastAPI, OpenAI API (gpt-4o / gpt-4o-mini) |
| 클라이언트 | Kotlin, Jetpack Compose, Retrofit, OkHttp(SSE), Moshi |
| 통신 | REST + Server-Sent Events |

## 개발 히스토리

| 시기 | 내용 |
|---|---|
| 4월 | ChatGPT API 연동 단순 Q&A 프로토타입 |
| 5~6월 | Flask → FastAPI 기반 agent 서버 전환, 대화 요약·기억 기능, 시간 조회 tool 개발 |
| 7월 (본 저장소 버전) | 서버-안드로이드 실시간 연동, 설치 앱 기반 실행 로직, SSE 스트리밍 응답 완성 |
| 8월 | GUI-META / AndroidWorld 데이터셋 선별·구축 (별도 산출물, 코드 미포함) |

## 폴더 구조

```
fluise/
├── server/                # FastAPI 기반 GPT 에이전트 서버
│   ├── main.py
│   ├── gpt_agent_sdk/
│   │   ├── core.py         # GPTAgent, MemoryAgent(파일 영속화 버전)
│   │   ├── memory_agent.py # MemoryAgent 리팩터링 버전
│   │   ├── tool_agent.py   # 앱 키워드 매칭, 명령 큐
│   │   └── mobile_agent.py # 모바일 제어 인텐트 처리
│   ├── requirements.txt
│   └── .env.example
└── app/                   # 안드로이드 클라이언트 (Kotlin, Jetpack Compose)
    └── app/src/main/java/com/example/myapp/
        ├── MainActivity.kt        # Compose UI, ChatViewModel
        ├── CommandService.kt      # 포그라운드 서비스(앱 목록 등록, 명령 폴링)
        ├── AgentClient.kt         # 서버 통신 클라이언트
        └── PackageChangeReceiver.kt
```

## 실행 방법

### 서버
```bash
cd server
pip install -r requirements.txt
cp .env.example .env   # OPENAI_API_KEY 입력
uvicorn main:app --reload --host 0.0.0.0 --port 8000
```

### 안드로이드 앱
1. Android Studio로 `app/` 폴더 열기
2. `app/src/main/java/com/example/myapp/AgentClient.kt`와 `MainActivity.kt`의 `SERVER`/`BASE_URL`을 서버 주소로 수정 (에뮬레이터는 기본값 `http://10.0.2.2:8000` 유지)
3. 빌드 및 실행

## 팀 및 지도

- 3인 팀 프로젝트로 진행했으며, 본인은 팀 리더는 아니었지만 서버(FastAPI 에이전트)와 안드로이드 클라이언트 구현의 상당 부분을 담당했다.
- 지도교수: 이선재 교수님

## 참고

- `gpt_agent_sdk/core.py`의 `MemoryAgent`와 `gpt_agent_sdk/memory_agent.py`는 같은 역할을 하는 두 버전이 함께 남아있다. 개발 중 리팩터링 과정에서 생긴 것으로, 실제 서비스에는 `core.py` 버전이 사용됐다.
- 이 저장소는 팀 프로젝트 중 본인이 담당한 부분을 중심으로 정리한 것이다.
