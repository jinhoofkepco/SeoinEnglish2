# CLAUDE.md — 작업 지침 (새 세션 필독)

Emoji English — Android 멀티모듈 영어 학습 앱. 이 파일은 새 세션이 맥락을
그대로 잇도록 정리한 것이다. **작업 시작 전 `git log --oneline -10`과 `docs/`를
먼저 읽어라.**

## 빌드 / 설치 / 로그 (Windows)

```powershell
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
cd C:\Users\SAMSUNG\Documents\Codex\UsersSAMSUNGDocumentsCodexSeoinEnglish2
.\gradlew.bat :app:assembleDebug --console=plain -q   # 컴파일만
.\gradlew.bat :app:installDebug  --console=plain -q   # 기기 설치
```
- 빌드는 **오래 걸리니 `run_in_background: true`**로 돌리고 알림을 기다려라.
- 컴파일 검증만 할 땐 `assembleDebug`. 설치 실패 "No connected devices"는 기기
  연결/잠금해제 문제(코드 무관).
- adb: `C:\Users\SAMSUNG\AppData\Local\Android\Sdk\platform-tools\adb.exe`
  - 기기: `R9TY603B5FX` (Galaxy Tab, Android 15). 스크린샷은
    **Bash로 바이너리 리다이렉트**(`adb exec-out screencap -p > x.png`).
    PowerShell `>`는 PNG를 깨뜨린다.
  - 음성 로그: `adb logcat -s SeoinVoice:D` / 크래시: `adb logcat -b crash -d`
- 툴체인: AGP 9 빌트인 Kotlin(모듈에 `kotlin-android` 미적용), compileSdk 37, minSdk 26.

## 절대 규칙

- **커밋은 사용자가 시킬 때만.** 자동 커밋 금지. push도 금지(승인 필요).
- **스텝 상호 불가침**: `steps/*`는 서로 import 안 함, 다른 feature import 안 함,
  화면 이동 안 함. `feature:step-api` 계약(`StepFeature`/`StepSession`/`StepSpec`/
  `StepResult`)은 **수정 금지** — 필요하면 멈추고 보고. 상세는
  `docs/STEP_AUTHORING_GUIDE.md`.
- **`core:model` 변경은 최후의 수단.** 새 콘텐츠 구조는 스텝 `params`에 자급자족으로
  담는다(`story_comic`/`passage_read` 선례).
- 큰 코드 변경은 **Write로 파일 통째** 쓰는 게 부분 Edit보다 안전(태그/매칭 사고↓).

## 음성(core:voice) — 주의

- `WebViewVoiceGateway`는 ChatGPT 웹뷰를 JS로 구동하는 단일 @Singleton. 매우 민감.
- **턴 종료는 RMS(오디오) 기반이 정상 동작 버전**(= 베이스라인 커밋 5273d11).
  "텍스트 기반 종료/프로브 경량화/폴링 변경"을 시도했다가 **학생 턴을 못 잡아 되돌림**.
  함부로 다시 건드리지 말 것. 바꿔야 하면 A/B로 신중히.
- `LocalTts`(즉답 낭독) = `gateway.speak()`. GPT 음성 코칭 = `requestVoice()`.
- 알려진 이슈: ChatGPT 음성 모드 오디오 부하로 **WebView 렌더러가 가끔 죽음**
  (logcat `SyncReader timed out` → `Renderer process crash`). 현재는 핸들 없음
  (베이스라인으로 되돌리며 `onRenderProcessGone` 복구 코드도 빠짐). 재발 시 재논의.

## 보이스 프롬프트 규칙

- **항상 간결하게.** 예시만 주고 판단은 보이스에게 맡긴다(길면 인지 실패).
  payload는 한 줄 + 예시. 카탈로그는 `core/voice/StepPromptKind.kt`(READ_ALONG/
  EXPLAIN/QUIZ_VOCAB/QUIZ_CONTEXT/FREE_TALK).
- EXPLAIN instruction은 `"$payload 할 수 있는 한 가장 느리게 말해줘."` (이전의
  "3문장 이상~" 지시는 삭제됨). 공통 접두 `SPEAK_STYLE`(영어)와 한국어 payload가
  섞이는 구조라, 한국어 응답을 원하면 payload에 "한국어로"를 명시.

## 디자인 톤

- 색은 **MaterialTheme colorScheme** 사용(하드코딩 팔레트 지양, 다크모드 호환).
- 지문 스텝(passage_read)은 "안정감" 우선: 현재 문장도 같은 폰트 크기 유지(레이아웃
  출렁임 금지), 강조는 색/밑줄로만. 버튼은 작은 원형, 라벨 텍스트 최소.

## 현재 진행 상황 (이번 세션 누적, 미커밋)

`git status`에 다음이 미커밋 상태. 모두 컴파일 통과 확인됨:
- **story_comic**: 전체만화 + 찾기퀴즈/따라읽기/단어수집. 상단 제목·설명 제거(공간↓).
- **passage_read**(신규 모듈 `steps/passageread`): 지문 탐험. 문단 구조(`paragraphs`
  + `overlookQuestion`), 스크롤 본문 + 현재 문장 중앙 고정, 청크 색교대 표시,
  하단 원형 버튼 1열(이전/따라읽기/EN/한/다음), 문단 끝 🔭 마커. JSON은
  `u02_butter.json`에 문단·청크 한글뜻 포함.
- **하단 허브 통합**(AppShell/VoicePanel/MainViewModel): 보이스+마스터를 한 줄로,
  접으면 좌하단 동그라미. 콘텐츠 가림 버그를 동적 padding으로 수정.
- **마이크 자동 끄기**: `LessonPlayerViewModel.sessionFor.speak()`에서 다음 액션
  (낭독) 시 `setMicOpen(false)` — 모든 스텝 공통.
- **보이스 프롬프트 간결화** + EXPLAIN 정리(위 참조).

## 남은 작업 (사용자 요청, 미완성)

1. **하단 허브 정리**: "마이크 토글 + 수동/AUTO 배지" 제거. "대화" 버튼은
   유지(대화 원할 때만 사용). → 그 자리를 **"그림" 버튼**으로 대체.
2. **그림창(2번째 WebView)**: 그림 버튼 누르면 **화면 가운데**에 접혔다 펴지는
   웹뷰 패널. ChatGPT에 접속해 단어 그림을 찾아 표시("churner" 등), 못 찾으면
   그려달라고. **문장의 단어들을 선택지(칩)로** 띄워 누르면 그 단어 그림 요청
   프롬프트를 그 웹뷰에 주입. 음성 WebView와 **별도 인스턴스**(쿠키는 공유).
   - 설계 미결: 그림창은 공통(하단 허브)인데 "현재 문장 단어"는 스텝 상태 →
     스텝↔그림창 연결 방식 고민 필요(예: `PictureController` 싱글톤에 스텝이
     단어 push, 계약 동결이라 StepSession 변경 없이).
3. **"안내 문구 → 작은 ! 토스트"**: "문장에서 단어 조각을 눌러보세요" 같은 안내를
   상시 표시 말고, 아주 작은 `!` 아이콘만 두고 누르면 위에 1~2초만 떴다 사라지게.
4. story_comic 단어 EXPLAIN이 단어만 느리게 읽는 형태가 됨(공통 EXPLAIN 변경
   여파) — 필요 시 payload를 "{단어} 뜻을 쉬운 영어로 설명해줘"로 보정.

## 참고 문서

- `docs/STEP_AUTHORING_GUIDE.md` — 스텝 작성 계약/패턴 (지문·청크 작업 필독).
- `docs/HANDOFF_voice_comic.md` — 음성/만화 구조 이력.
- 전작(개념·JSON 규칙 참고용, 복붙 금지):
  `C:\Users\SAMSUNG\Documents\Codex\2026-05-30\py-json-1-chunk-2-chunk`
