# 핸드오프 — 음성(Voice) + 단어만화(WordComic) 작업

이 문서는 다른 머신/세션(집 Claude Code 등)에서 **이어서 작업**할 수 있도록 현재 상태와
다음 할 일을 정리한 것입니다. (이 채팅 자체는 동기화되지 않으니 이 문서가 인수인계 기준.)

빌드: `JAVA_HOME="<Android Studio>/Contents/jbr/Contents/Home" ./gradlew :app:assembleDebug`
실행 로그: `adb logcat -v time SeoinVoice:V "*:S"` (태그 `SeoinVoice`)

---

## 1) 음성 아키텍처 (3계층 + 영구 패널) — 동작 확인됨

`core:voice`
- **엔진** `WebViewVoiceGateway` — 단일 ChatGPT WebView(@Singleton, 스텝 이동에도 유지).
  `TRPG_webviewGPT/MainActivity.kt`의 JS 빌더 포팅(voice 버튼/ready, composer 주입+user
  bubble 확인, 응답 probe, **media RMS probe**, audio duck).
  - **마이크**: GPT 버튼 클릭이 음성 세션을 망가뜨려(텍스트로 답함) → **`AudioManager.setMicrophoneMute`**
    로 시스템 마이크 자체를 음소거. `micOpen` 상태가 항상 신뢰 가능.
  - **턴 종료 판정**: 보이스 모드에선 텍스트 완료(stop 아이콘)가 안 끝나므로 **스피커(오디오)가
    조용해지면**(`QUIET_STREAK_POLLS=2` ≈300ms) 즉시 종료. `awaitTurnComplete()`가 텍스트+오디오를
    한 루프에서 감시하며 `speaking`(설명중)을 **실시간** 반영.
  - **프라이밍**: `primedOnce`로 앱 실행당 1회만(책 재진입 시 재전송 안 함). 2단계(역할 프롬프트
    → 키보드 nudge).
- **세션** `VoiceSession`/`DefaultVoiceSession` — "세트 동안 한 보이스".
  - `setMicManual`(우리 버튼) = autoGate off + **manualOverride 고정**(목표③: 메인에서 켜면 auto 재진입 안 함).
  - `runPrompt` = **Mutex 직렬화 + 진행 중이면 새 탭 무시**(연타 적체 방지).
  - 턴 끝나면 마이크 자동 ON(autoGate·!override일 때).
  - `endSet` 시 시스템 마이크 원복(unmute).
- **프롬프트 카탈로그** `StepPromptKind`{READ_ALONG,EXPLAIN,QUIZ_VOCAB,QUIZ_CONTEXT,FREE_TALK}
  + `VoicePrompt.toTurnScript()`. EXPLAIN = "단어를 3문장+쉬운 예문, 아주 느리게" (단어만화에서 사용).
  - `VoicePrompt`에 `kind`/`payload` 추가했지만 **step-api(StepSession.requestVoice)는 그대로**(동결 유지).

UI: `feature:main/VoicePanel.kt` — 하단바 위 상주 패널. **3-state**(닫힘 스트립 / 최소화 열림(WebView를
460dp로 레이아웃하되 화면엔 10dp 띠만 클립 = "열린 흉내") / 열림). 접기 버튼이 순환.
WebView는 패널이 열려 있어야(부착돼야) 동작하므로, 세트시작·프롬프트 시 자동으로 최소화-열림.

DI: `VoiceModule`이 `WebViewVoiceGateway`/`DefaultVoiceSession` 바인딩(프로덕션). Mock/Fake는 프리뷰/테스트용.
`MainActivity`가 RECORD_AUDIO 런타임 요청.

### 음성 관련 알려진 이슈 / 개선 여지
- **간헐적으로 GPT가 음성 없이 텍스트로만** 답하는 턴이 있음(타이핑 입력을 텍스트로 받는 경우). 빈도 거슬리면
  프롬프트에 "음성으로 답해줘" 추가 등 시도.
- 대화 **rename**은 탭 타이틀만 설정(폴더 이동/이름은 webview 좌측 메뉴 수동).
- `templates.json`/persona는 placeholder — `서인영어_brain2` 문서 받으면 주입(코드 무수정 목표).

---

## 2) 단어만화(WordComic) — Phase 1·2 완료, Phase 3 남음

공유 설계(`프롬프트 JSON으로 4컷 만화`)를 이식. 핵심: **만화 전체가 JSON 데이터**, 고정 렌더러가 조립.

- 모델 `core:model/ComicScript.kt`: `ComicScript{panels}`, `ComicScene{bg,caption,sprites,bubble,highlight}`,
  `ComicSprite{char|src, x,y(%), scale, anim}`, `ComicBubble{anchor,text}`. **char(이모지)·src(이미지경로) 둘 다 수용.**
- 렌더러 `steps:wordcomic/ComicStrip.kt` (Compose):
  - 폰=**세로 1열**, 태블릿=**2×2** (`smallestScreenWidthDp>=600`).
  - 세로 스크롤, 신 간격 20dp.
  - 스프라이트: %좌표 배치(클램프 x13~87 y18~82), 크기 `unit*0.32*scale`(scale≤1.6).
  - bg 그라데이션 프리셋(swamp/pond/forest/sky/snow/lava/night/sunset).
  - 캡션에 highlight 단어 강조, 말풍선(anchor).
  - **Phase 2 모션 완료**: `anim` = bounce/float/jump (infiniteTransition, 위로 튀는 루프).
- `WordComicFeature`: 정적 만화 **SwampDietPaleAlgae**(개구리: 늪→다이어트→창백→이끼) 표시. **칸 탭 → 그
  단어 EXPLAIN 음성**. 진입 시 자동 완료(다음 단계 활성).

### Phase 3 (다음 작업) — LLM이 JSON 생성
- 목표: "괴물/우주 키워드로 만화 만들어줘" → **LLM이 ComicScript JSON 생성** → 렌더러가 그림.
- 인프라 이미 있음: ChatGPT WebView 게이트웨이(프롬프트로 JSON 요청 후 파싱) 또는 마스터 대시보드 JSON 임포트(M7).
- 안정성 규칙(공유 설계): bg/anim은 **고정 목록 이름만**, 좌표/scale 클램프, 결과 JSON 교사 편집용 인스펙터.
- 제안 경로: (a) 마스터 대시보드에 "키워드→만화 생성" + JSON 미리보기/편집, (b) 생성된 JSON을 unit content/asset에 저장.

---

## 3) 그 외 이번 세션 UI 변경
- 시스템 인셋(상태바/내비바) 패딩 — AppShell.
- 홈 "오늘의 할일" 리스트에 완료 단원 **"✅ 다했어요"**(session_end 트레이스 기반).
- 플레이어: 완료해도 화면 유지(닫지 않음). "다음 단계" 버튼이 하단바/패널에 안 가리게 패딩.
- 마스터 대시보드 상단에 학습 화면과 **동일한 네비게이터 바**(최근 단원 스텝 칩).

## 다음 세션 추천 순서
1. WordComic **Phase 3**: 키워드→LLM→ComicScript JSON 생성 + 렌더링(+편집 인스펙터). ✅ 완료
2. 음성 간헐적 텍스트-응답 완화.
3. 나머지 스텝(voiceexplain/similarcard/shadowing/question/chunk) 실제 구현.
   — similarcard/question/chunk ✅ 완료. shadowing/voiceexplain 남음.

---

## 4) 전작(py-json-1-chunk-2-chunk) 이식 — 전체만화 (story_comic) ✅

이전 프로젝트의 단어 파트 3종(전체만화·한컷만화·카드게임) 중 **전체만화**를
`steps:storycomic` 모듈(type `story_comic`)로 이식 완료. 전작 소스:
`2026-05-30/py-json-1-chunk-2-chunk` — `DataComicPanelView.kt`(렌더러),
`showVocabComicStudyStage`(흐름), `JSON_RULES_TABLE_V4_FULL_BODY_COMIC_GAME.md`(스키마).

**이식한 설계 고민:**
- 한 컷씩 크게 (그리드 X) — 집중. 컷 등장 시 캡션 자동 TTS + Replay.
- 오늘의 단어 칩 + 캡션 내 단어 하이라이트·탭(LinkAnnotation) → 즉시 설명 TTS.
- 연출 어휘 전부 보존: bg 10종 그라데이션 + 할프톤 도트, mood(red/dusk/warm),
  fx(focus/speed/shake), 카메라 zoom(pushin/pan/kenburns/shakezoom — 9s/5s/180ms
  타이밍 전작 동일), sfx 효과음(테두리+기울임), 스프라이트 anim 9종 + rotate/flip,
  climax 금색 이중 테두리, night 배경 색 반전.
- 만화 전체가 step params JSON 안에 자급자족 → 스텝 상호 불가침 유지(core:model 무변경).
- 안정성 규칙: bg/anim 고정 목록 검증, 좌표/scale 클램프 (LLM 생성 대비).

**부수 작업**: `core:voice/LocalTts.kt` 신설 — `VoiceGateway.speak()`가 no-op이었던 것을
Android TTS(속도 0.8)로 실구현. 모든 스텝의 `session.speak`가 이제 실제로 소리남.

**남은 이식 대상**: 한컷만화(word spotlight, `quizPanel`), 카드게임(4지선다 이모지
reflex game — 전작은 vocabulary[]에서 자동 생성). 결과에 따라 word_comic 스텝 삭제 검토.
