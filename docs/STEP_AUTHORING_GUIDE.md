# 스텝 작성 가이드 — 지문 읽기 / Chunk Study 작업용

이 문서는 **다른 세션/머신에서 새 스텝(지문 읽기·chunk study)을 작업**할 때 알아야 할
모든 것을 한 파일에 담은 핸드오프다. 코드 전체를 학습시키되 **스텝 모듈만 변경**하는
시나리오를 전제로 한다.

> 한 줄 요약: 새 스텝은 `steps/<name>/`에 **독립 모듈**로 만들고, `StepFeature` 계약만
> 구현한다. 다른 모듈·공유 모델·다른 스텝은 **건드리지 않는다**. 화면에 필요한 데이터는
> 가능하면 그 스텝의 `params` JSON 안에 **자급자족**으로 넣는다(`story_comic` 선례).

---

## 0. 절대 규칙 (먼저 읽기)

| # | 규칙 | 이유 |
|---|---|---|
| R1 | **`feature:step-api`의 계약은 수정 금지** (`StepFeature`/`StepSession`/`StepSpec`/`StepResult` 시그니처). 변경이 필요하면 멈추고 보고. | 6개 스텝 + Player + Master가 이 계약에 동결되어 동시 개발됨. |
| R2 | 스텝은 **다른 스텝을 import 하지 않는다**. 다른 feature(`player`/`master`/`home`)도 import 금지. | 스텝 상호 불가침(§0.2). 모듈 하나 빼도 앱이 빌드돼야 함. |
| R3 | 스텝은 **화면 이동을 하지 않는다**. Activity/Nav 접근 금지. | 이동은 Player가 공통 CTA로 소유(§0.3). 스텝은 `complete()`만 신호. |
| R4 | `core:model`(공유 데이터 클래스) 변경은 **최후의 수단**. 새 콘텐츠 구조는 스텝 `params`에 담아 자급자족. | 모델을 바꾸면 모든 스텝·검증기·Player가 영향. `story_comic`이 params 자급자족 선례. |
| R5 | 커밋은 사용자가 시킬 때만. | 사용자 지시. |

새 스텝이 의존해도 되는 모듈: **`feature:step-api`, `core:designsystem`**(공용 UI),
필요 시 `core:model`(읽기 전용 데이터 클래스), `core:voice`(VoicePrompt/StepPromptKind),
`core:data`(거의 불필요 — `story_comic`이 holder 하나 쓴 정도). 그 외 금지.

---

## 1. 모듈 구조 & 의존 방향

```
app  ──(조립만)── feature:main / home / player / master / step-api
                        │
   steps:* ────────────┤  각 스텝은 독립 모듈. app이 유일하게 모음.
                        │
feature:step-api ─ api → core:model, core:voice   (← 동결된 계약 계층)
core:designsystem ─→ core:model                    (공용 컴포넌트·테마)
core:content / core:data ─→ core:model             (레슨 로딩 / 저장)
```

- 스텝 모듈은 `app`만이 `implementation(project(":steps:<name>"))`으로 모은다.
- `settings.gradle.kts`의 include를 지워도 **앱은 여전히 빌드**돼야 한다(그 type은
  "지원하지 않는 Step" 카드로 렌더, §0.6). 이게 상호 불가침의 안전망.

---

## 2. 스텝 계약 — `StepFeature` (수정 금지, 구현만)

`feature/step-api/.../StepFeature.kt`:

```kotlin
interface StepFeature {
    val type: String                                  // JSON steps[].type 와 1:1 (예: "passage_read")
    fun parseSpec(stepJson: JsonObject, content: LessonContent): StepSpec   // 자기 params 파싱·검증
    @Composable fun StudentScreen(spec: StepSpec, session: StepSession, modifier: Modifier)
    @Composable fun MasterView(spec: StepSpec, trace: StepTraceSnapshot, modifier: Modifier)
}

interface StepSpec { val stepId: String }            // 각 스텝이 자기 data class로 구현
class StepSpecParseException(message: String, cause: Throwable? = null) : Exception(...)
```

- `parseSpec`은 **자기 type의 params만** 파싱한다. 실패 시 `StepSpecParseException`을 던지면
  Player가 파싱 오류 카드를 보여준다(앱 크래시 X).
- `StudentScreen` = 학생 학습 화면. `MasterView` = 교사용 읽기 전용 기록 화면.

### parseSpec 입력
- `stepJson` = 그 스텝 객체 전체 `{ "id", "type", "params" }`.
- `content` = 단원의 공유 콘텐츠(`LessonContent`). 단어/지문 등을 id로 참조할 때 사용.

### StepJson 헬퍼 (`feature/step-api/.../StepJson.kt`)
```kotlin
stepJson.stepId()                  // "id" (없으면 parse 예외)
stepJson.params()                  // "params" JsonObject (없으면 빈 객체)
params.string("k") / requireString("k")   // 문자열 (require는 없으면 예외)
params.stringList("k")             // List<String>
```
복잡한 params(중첩 배열/객체)는 `kotlinx.serialization.json`으로 직접 파싱한다.
`story_comic`의 `StoryComicModels.kt`(`parseStoryComic`)가 중첩 파싱 + 고정 목록 검증 +
클램프의 좋은 예시.

---

## 3. `StepSession` — 스텝의 유일한 외부 통로 (수정 금지)

`feature/step-api/.../StepSession.kt`. Player가 구현해 넘겨준다
(`LessonPlayerViewModel.sessionFor`). 스텝은 이것만 호출한다:

```kotlin
interface StepSession {
    val content: LessonContent                 // 단원 공유 콘텐츠 (읽기 전용)
    val savedResult: StateFlow<StepResult?>     // 이 회차 저장 결과 (재진입 시 UI 복원)
    fun trace(action: String, detail: Map<String,String> = emptyMap())  // 학습 기록 1줄
    fun complete(result: StepResult)            // ★ 완료 신호 → Player가 "다음 단계" CTA 활성
    fun requestVoice(prompt: VoicePrompt)       // GPT 보이스 코칭 턴 (상호작용)
    fun speak(text: String, lang: String = "en-US")  // 즉시 로컬 TTS (낭독)
}
```

핵심 동작:
- `complete(result)` 를 부르면 Player가 **"다음 단계" 버튼을 띄운다.** 스텝이 직접 이동 X.
  진입만으로 끝나는 스텝(읽기 등)은 `LaunchedEffect(Unit){ complete(Completed()) }`로 즉시 완료.
- `trace(action, detail)` — `studentId/occurrence/sessionId/시각`은 Player가 자동으로 채운다.
  스텝은 `action`(예: `"chunk_seen"`)과 `detail`(예: `{"index":"2"}`)만 넘긴다.
- `speak()` = **로컬 Android TTS**(즉답, 캡션·단어 낭독용). `LocalTts`, 느린 속도 0.8.
- `requestVoice()` = **GPT 보이스 코칭 턴**(설명·문답). 아래 §6.
  - 둘의 차이: `speak`는 한 문장 읽기, `requestVoice`는 상호작용(설명/질문/판정).

---

## 4. `StepResult` (완료 시 넘기는 결과) — `core:model/StepResult.kt`

```kotlin
sealed interface StepResult {
    data class Completed(...)                                   // 그냥 완료
    data class Scored(selected, answer, score, maxScore, ...)   // 퀴즈 채점
    data class ShadowingRecorded(practicedSentences, totalSentences, audioFilePaths, ...)
    data class VoiceCompleted(targetType, targetId, ...)
}
```
- 지문 읽기 → 보통 `Completed`. chunk 이해 퀴즈가 있으면 `Scored`.
- **새 결과 타입이 꼭 필요하면** 이건 `core:model` 변경(R4)이라 멈추고 보고. 우선 기존 타입으로
  표현 가능한지 검토(예: 청크 N개 중 M개 학습 → `Scored(score=M, maxScore=N)`).

---

## 5. `MasterView` & 기록 (`StepTraceSnapshot`)

- `MasterView`는 **넘겨받은 `StepTraceSnapshot`만** 그린다(라이브 학생 상태 X, §0.4).
- 헬퍼(`feature/step-api/.../StepTraceFormat.kt`):
  - `trace.resultSummaries()` → `List<String>` (회차별 결과 요약)
  - `trace.timeOrderedActivities()` → `List<TraceActivity{timeMillis, action, detail}>`
- 공용 스캐폴드 `DummyMasterScaffold(title, resultLines, activities, modifier)`를 쓰면
  대부분의 스텝 MasterView가 한 화면으로 끝난다(`steps:chunk`/`shadowing` 참고).
  지문/청크 전용 뷰(예: 어떤 청크에서 막혔는지)를 원하면 직접 그려도 된다.

---

## 6. 보이스 연동 — `core:voice`

```kotlin
session.requestVoice(VoicePrompt(
    templateId = "passage_read",
    kind = StepPromptKind.READ_ALONG,     // 아래 카탈로그
    payload = "읽어줄 문장 or 설명 대상",
    contextLabel = "지문 읽기 · 문장 3",   // 시트 헤더용
))
```

`StepPromptKind` (카탈로그, `core/voice/.../StepPromptKind.kt`):
| kind | 동작 | 마이크 |
|---|---|---|
| `READ_ALONG` | payload를 **천천히 한 번 낭독**, 아이가 따라 읽기 | 안 열림 |
| `EXPLAIN` | payload를 아주 쉬운 영어로 3문장+예문 설명 | 안 열림 |
| `QUIZ_VOCAB` | 단어 질문 1개 → 듣기 → 판정 | 열림(ASK) |
| `QUIZ_CONTEXT` | **내용/맥락 질문** 1개 → 듣기 → 판정 | 열림(ASK) |
| `FREE_TALK` | 자유 대화 | 수동 |

- 지문 읽기/청크에 특히 유용: **`READ_ALONG`**(문장 따라 읽기), **`QUIZ_CONTEXT`**(독해 확인 문답).
- `payload`는 읽어줄 실제 텍스트. 한 문장씩 넘기면 문장 단위 코칭이 된다.
- 스텝은 **turn script를 직접 쓰지 않는다.** kind만 고르면 `toTurnScript()`가 규칙(느리게/쉽게/
  태그 침묵/평가 루브릭)을 자동 적용. 마이크 auto-gate·무음 자동 복귀도 세션이 처리.
- `speak()` vs `requestVoice()`: 단순 낭독은 `speak`(즉답), 상호작용·따라읽기·문답은 `requestVoice`.

---

## 7. 공용 UI — `core:designsystem`

- `ContentColumn { ... }` — 840dp 폭 제한 + 중앙 정렬(폰 full-bleed, 태블릿 중앙). 보통 스텝 화면 루트.
- `DummyStudentScaffold(...)` / `DummyMasterScaffold(...)` — 빠른 학생/마스터 스캐폴드.
- `formatClock(millis)` — 기록 시각 표시.
- `stepTypeLabel(type)` / `stepTypeEmoji(type)` — 네비게이터 칩 라벨/이모지. **새 type 추가 시 여기
  두 함수에 한 줄씩 추가**(이건 designsystem이라 스텝 독립성 안 깨짐).
- 적응형 규칙: 하드코딩 폭/`if(isTablet)` 금지. `smallestScreenWidthDp >= 600`으로 분기
  (`story_comic`/`ComicStrip` 참고).

---

## 8. 새 스텝 추가 체크리스트

1. `steps/<name>/build.gradle.kts` 생성 — 기존 스텝 것 복사. 의존: `:feature:step-api`,
   `:core:designsystem`(+필요 시 `:core:voice`). `namespace`만 바꾼다.
2. `settings.gradle.kts`에 `include(":steps:<name>")` 추가.
3. `app/build.gradle.kts`에 `implementation(project(":steps:<name>"))` 추가.
4. `XxxFeature.kt` 작성: `type`, `parseSpec`, `StudentScreen`, `MasterView` + Hilt 바인딩:
   ```kotlin
   @Module @InstallIn(SingletonComponent::class)
   interface XxxBindModule {
       @Binds @IntoMap @StringKey("passage_read")
       fun bind(impl: XxxFeature): StepFeature
   }
   ```
5. `core:designsystem/StepLabels.kt`에 라벨·이모지 한 줄씩.
6. 단원 JSON `steps[]`에 `{ "id", "type":"passage_read", "params": {...} }` 추가.
7. `@Preview` + `FakeStepSession`으로 **앱 없이 단독** 미리보기(아래 §9).

라벨이 없거나 모듈을 안 넣었을 때 앱이 안 깨지는지(=상호 불가침)도 확인.

---

## 9. 단독 개발 — `FakeStepSession`

`feature/step-api/.../FakeStepSession.kt` — 앱·Player·DB 없이 스텝을 빌드/프리뷰.
trace/voice/complete 호출을 기록한다. `SampleContent.restaurant`가 기본 콘텐츠.

```kotlin
@Preview(name="phone", device="spec:width=411dp,height=891dp")
@Preview(name="tablet", device="spec:width=1280dp,height=800dp,dpi=240")
@Composable private fun Preview() {
    XxxFeature().StudentScreen(spec = XxxSpec("s1", ...), session = FakeStepSession(), modifier = Modifier)
}
```

---

## 10. 빌드 / 설치 (Windows)

```powershell
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
cd <repo>
.\gradlew.bat :app:assembleDebug         # 빌드만
.\gradlew.bat :app:installDebug          # 기기에 설치
```
- 툴체인: AGP 9 빌트인 Kotlin(모듈에 `kotlin-android` 적용 안 함), compileSdk 37, minSdk 26.
- 로그: `adb logcat -s SeoinVoice:D` (보이스), 스텝 trace는 마스터 대시보드에서 확인.

---

## 11. 지문 읽기 / Chunk Study — 콘텐츠 모델 선택

### 현재 `core:model`의 지문 구조 (단순) — `Content.kt`
```kotlin
LessonContent { words, comic, passage }
Passage { id, title, text, sentences: List<String>, chunks: List<Chunk>, questions: List<Question> }
Chunk   { id, text, meaningKo }
Question{ id, type, question, choices, answer }
```
- 기존 스텝: `steps:chunk`(`chunk_interpret`)=청크 카드 넘기기(구현됨),
  `steps:shadowing`(`shadowing`)=더미. 둘 다 이 단순 모델을 읽는다.

### 권장: 풍부한 청크 데이터는 **params 자급자족**으로
전작(아래 §12)의 chunk study는 훨씬 풍부했다: 문장별 `chunkSets`(여러 청킹 모드),
청크의 `startChar/endChar`(본문 substring과 정확히 일치), 그리고 **본문 청크를 직접 눌러
답하는 독해 확인(comprehensionChecks)**. 이 수준을 원하면 **`core:model`을 늘리지 말고**
(R4) 새 스텝의 `params`에 자급자족 JSON으로 담아 그 스텝의 `StepSpec`이 파싱한다 —
`story_comic`이 만화 전체를 params에 담은 것과 동일한 패턴.

예시 골격(파라미터 설계는 자유):
```json
{ "id": "s1", "type": "passage_read", "params": {
  "title": "Lunch Time",
  "sentences": [
    { "id": "s1", "text": "She wants to order pasta.",
      "chunks": [
        { "id": "c1", "text": "She wants",        "startChar": 0,  "endChar": 9 },
        { "id": "c2", "text": "to order pasta.",   "startChar": 10, "endChar": 25 }
      ] }
  ],
  "checks": [
    { "id": "q1", "sentenceId": "s1", "question": "What does she order?",
      "answerChunkId": "c2" }      // 정답 = 본문의 한 청크 (별도 선택지 X)
  ]
}
```
- 규칙(전작 검증): `text.substring(startChar, endChar) == chunk.text`. 정답은 **한 청크에
  온전히** 담겨야 한다(여러 청크에 걸치면 그 질문은 만들지 않음). 대명사/접속사만인 청크는
  정답으로 부적합. 상세는 §12의 V4 문서 7~10절.

### 보이스로 "읽기+이해" 묶기
- 문장 등장 → `speak(text)` 또는 `requestVoice(READ_ALONG, payload=text)`로 낭독/따라읽기.
- 청크 단위 일시정지 후 따라 말하기 → 문장별 `READ_ALONG`.
- 독해 확인 → 본문 청크 탭으로 답 고르기(전작 방식) + 필요 시 `QUIZ_CONTEXT`로 음성 문답.
- 완료 신호: 끝 문장 도달 시 `complete(Completed())`(또는 확인 점수 시 `Scored`).

---

## 12. 전작 자료 (chunk study 원형) — 참고 위치

이전 프로젝트 `C:\Users\SAMSUNG\Documents\Codex\2026-05-30\py-json-1-chunk-2-chunk`:
- **`JSON_RULES_TABLE_V4_FULL_BODY_COMIC_GAME.md`** — paragraphs▸sentences▸chunkSets,
  comprehensionChecks 스키마·규칙·예시·검증 체크리스트(가장 중요).
- `app/.../chunk/ManualChunkLogic.kt`, `app/.../ui/views/ManualChunkView.kt` — 청크 분할/표시 로직.
- `app/.../MainActivity.kt` — `showAfterComicQuizStage`, chunk reading / comprehension check 흐름.
- `app/src/main/assets/lessons/*/lesson.json` — 실제 chunkSets/comprehensionChecks 데이터 예시
  (예: `the_color_of_royalty_tyrian_purple`, `three_types_matter_*`).
- 단, 전작은 단일 Activity·전통 View 기반. **그대로 복붙 금지** — 개념·JSON 규칙·UX만 가져와
  현 프로젝트의 Compose + StepFeature 계약으로 재구성한다.

> 이식 선례: `story_comic`(전작 word-comic → 현 프로젝트 스텝). `steps/storycomic/`와
> `docs/HANDOFF_voice_comic.md`(§4)를 보면 "전작 연출/데이터를 params 자급자족 스텝으로
> 옮긴" 방식이 그대로 chunk study에도 적용된다.

---

## 13. 빠른 참조 — 손대도 되는 것 / 안 되는 것

| 작업 | 가능? |
|---|---|
| `steps/<내 스텝>/` 안의 모든 파일 | ✅ 자유 |
| `core:designsystem/StepLabels.kt`에 라벨/이모지 추가 | ✅ (디스플레이용) |
| `settings.gradle.kts` / `app/build.gradle.kts`에 내 모듈 include | ✅ (조립) |
| 단원 JSON `assets/books/.../*.json`에 내 스텝 추가 | ✅ |
| `feature:step-api` 계약 파일 수정 | ❌ 멈추고 보고(R1) |
| `core:model` 데이터 클래스 수정/추가 | ⚠️ 최후수단, 우선 params 자급자족(R4) |
| 다른 `steps:*` / `feature:player|master|home` import·수정 | ❌ (R2) |
| 스텝에서 화면 이동 / Activity 접근 | ❌ (R3) |
