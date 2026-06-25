# passage_read3 — 암초 독해 모듈 구현 스펙 (DeepSeek 인계용)

이 파일 하나로 `PassageRead3Feature.kt`를 처음부터 끝까지 구현한다. 다른 파일은 건드리지 마라.
배선(build.gradle / settings / app dep / StepLabels)은 이미 끝나 있다. **너는 오직
`steps/passageread3/src/main/kotlin/com/seoin/emojienglish/steps/passageread3/PassageRead3Feature.kt`
한 파일만 작성**한다.

---

## 0. 절대 규칙
- package: `com.seoin.emojienglish.steps.passageread3`
- 의존 가능 모듈: `feature:step-api`, `core:designsystem`, `core:voice`, `core:model`. 그 외 import 금지.
  **다른 steps:* / feature:player|master|home 절대 import 금지. Activity/Navigation 접근 금지.**
- Jetpack Compose + Material3. 색은 `MaterialTheme.colorScheme` 사용(하드코딩 팔레트 지양).
- 결과물은 **컴파일되는 단일 .kt 파일**. 설명/마크다운 출력 금지, 코드만.

## 1. 구현할 계약 (정확한 시그니처 — 그대로 따른다)

```kotlin
// feature:step-api
interface StepFeature {
    val type: String
    fun parseSpec(stepJson: kotlinx.serialization.json.JsonObject,
                  content: com.seoin.emojienglish.model.LessonContent): StepSpec
    @androidx.compose.runtime.Composable
    fun StudentScreen(spec: StepSpec, session: StepSession, modifier: androidx.compose.ui.Modifier)
    @androidx.compose.runtime.Composable
    fun MasterView(spec: StepSpec, trace: com.seoin.emojienglish.model.StepTraceSnapshot,
                   modifier: androidx.compose.ui.Modifier)
}
interface StepSpec { val stepId: String }
class StepSpecParseException(message: String, cause: Throwable? = null) : Exception(message, cause)

// StepSession — 스텝의 유일한 외부 통로
interface StepSession {
    val content: LessonContent
    val savedResult: kotlinx.coroutines.flow.StateFlow<StepResult?>
    val voiceActive: kotlinx.coroutines.flow.StateFlow<Boolean>   // 기본 제공
    fun trace(action: String, detail: Map<String, String> = emptyMap())
    fun complete(result: StepResult)                 // ★ 완료 신호 (네비게이션 아님)
    fun requestVoice(prompt: VoicePrompt)            // GPT 보이스(원격). 읽기 구간엔 절대 호출 금지.
    fun speak(text: String, lang: String = "en-US") // 로컬 TTS(즉답)
}

// core:model StepResult (sealed)
StepResult.Completed(completed = true)
StepResult.Scored(selected: String, answer: String, score: Int, maxScore: Int, completed = true)

// core:voice
data class VoicePrompt(val templateId: String, val variables: Map<String,String> = emptyMap(),
                       val contextLabel: String = "", val kind: StepPromptKind = StepPromptKind.EXPLAIN,
                       val payload: String = "")
enum class StepPromptKind { READ_ALONG, EXPLAIN, QUIZ_VOCAB, QUIZ_CONTEXT, FREE_TALK }

// StepJson 헬퍼 (import: com.seoin.emojienglish.step.*)
fun JsonObject.stepId(): String
fun JsonObject.params(): JsonObject
fun JsonObject.string(key: String): String?
fun JsonObject.stringList(key: String): List<String>
// 그 외(중첩 배열/객체/Int/Float)는 kotlinx.serialization.json으로 직접 파싱.
//   JsonObject["k"] as? JsonArray / JsonObject / JsonPrimitive; (JsonPrimitive).intOrNull/contentOrNull

// designsystem MasterView 보조
data class MasterActivityRow(val time: String, val title: String, val subtitle: String, val detail: String)
@Composable fun DummyMasterScaffold(title: String, resultLines: List<String>,
                                    activities: List<MasterActivityRow>, modifier: Modifier = Modifier)
fun formatClock(millis: Long): String
// trace 헬퍼: import com.seoin.emojienglish.step.resultSummaries / timeOrderedActivities
//   trace.resultSummaries(): List<String>
//   trace.timeOrderedActivities(): List<TraceActivity{ timeMillis: Long, action: String, detail: String }>

// 단독 프리뷰: com.seoin.emojienglish.step.FakeStepSession()
```

Hilt 바인딩(파일 안에 포함):
```kotlin
@dagger.Module @dagger.hilt.InstallIn(dagger.hilt.components.SingletonComponent::class)
interface PassageRead3BindModule {
    @dagger.Binds @dagger.multibindings.IntoMap
    @dagger.multibindings.StringKey("passage_read3")
    fun bind(impl: PassageRead3Feature): StepFeature
}
class PassageRead3Feature @javax.inject.Inject constructor() : StepFeature { override val type = "passage_read3" ... }
```

## 2. params JSON 스키마 (자급자족 — 모든 암초 payload가 미리 들어온다)
명세 §1 사전추출/§7 캐시 복원과 동일 의미. 라이브 생성·STT는 배선 불가(계약 동결)라 **payload는 params로 사전 공급**.

```json
{ "id": "...", "type": "passage_read3", "params": {
  "passageId": "unit55",
  "title": "UNIT 55",
  "reefDensity": 1.4,
  "sentences": [
    { "index": 0,
      "text": "Mark Twain once wrote a funny story about a jumping frog.",
      "maxDepth": 0,
      "reefIds": ["u55_mt", "u55_story"] }
  ],
  "reefs": [
    { "id": "u55_mt", "sentenceIndex": 0, "anchorStart": 0, "anchorEnd": 10,
      "type": "nametag", "difficulty": 1,
      "nametag": { "answer": "person", "reassure": "정확히 몰라도 괜찮아, 계속 읽어." } },

    { "id": "u55_held", "sentenceIndex": 1, "anchorStart": 4, "anchorEnd": 8,
      "type": "detective", "difficulty": 3,
      "detective": { "blankSentence": "The contest was ___ every year.",
        "options": ["열렸다", "끝났다", "사라졌다"], "answerIndex": 0,
        "hint": "대회가 매년 '있었다'는 앞 문장을 보면 held는 열렸다는 뜻이야.",
        "meaning": "열렸다" } },

    { "id": "u55_skel", "sentenceIndex": 2, "anchorStart": 0, "anchorEnd": 7,
      "type": "skeleton", "difficulty": 4,
      "skeleton": {
        "chunks": [
          { "id": "c1", "text": "the people", "depth": 0, "role": "who" },
          { "id": "c2", "text": "had a great idea", "depth": 0, "role": "didWhat" },
          { "id": "c3", "text": "who really lived in Calaveras County", "depth": 1,
            "role": "whichPeople", "attachTo": "c1", "containsReefs": ["u55_cc"] },
          { "id": "c4", "text": "to have a frog-jumping contest", "depth": 1,
            "role": "whatIdea", "attachTo": "c2" }
        ],
        "questions": [
          { "q": "who?", "depth": 0, "answer": "c1" },
          { "q": "did what?", "depth": 0, "answer": "c2" },
          { "q": "which people?", "depth": 1, "answer": "c3", "attachTo": "c1" },
          { "q": "what idea?", "depth": 1, "answer": "c4", "attachTo": "c2" }
        ] } }
  ]
}}
```
- `anchorStart/anchorEnd` = 문장 text 내 **문자 오프셋**(인라인 팝업 위치 계산용).
- 파싱 실패(필수 누락) 시 `StepSpecParseException` throw.
- params.sentences가 비면 `content.passage?.sentences`로 폴백 가능(없으면 예외). 단 폴백 시 reefs 없음 → 읽기만.

## 3. 데이터 모델 (data class로)
PassageRead3Spec(stepId, data) / Data(passageId,title,reefDensity,sentences,reefs)
/ Sentence(index,text,maxDepth,reefIds)
/ Reef(id, sentenceIndex, anchorStart, anchorEnd, type, difficulty, payload: ReefPayload)
ReefPayload = sealed: Nametag(answer, reassure) / Detective(blankSentence, options, answerIndex, hint, meaning)
 / Skeleton(chunks:List<SkChunk>, questions:List<SkQuestion>)
SkChunk(id,text,depth,role,attachTo?,containsReefs:List<String>)
SkQuestion(q,depth,answer,attachTo?)
- 런타임 풀이 상태는 별도 mutableState(Map<reefId, ReefRunState{attempted,firstChoiceLabel?,correct?}>).

## 4. 화면 상태기계 (StudentScreen) — 명세 부록A 흐름
한 화면 안에서 `var phase by remember { mutableStateOf(Phase.Reading) }` 로 단계 전환.

### Phase.Reading (§3, §0.1 네트워크0)
- 문장을 **한 문장씩 하이라이트**(현재 문장만 강조, 나머지 흐리게). 스크롤 컬럼.
- 현재 문장의 reef 토큰(anchorStart..anchorEnd 범위)을 탭하면 **밑줄(underline) 토글** = "펜으로 줄치기".
  줄친 reefId set을 remember. trace("pr3_underline", {reef,index}).
- 하단 버튼: [이전] [다음]. 다음으로 마지막 문장 통과 시 phase=Quiz.
- 진행이 중반 통과 시 trace("pr3_gen_first", {underlined:n}) / 완독 시 trace("pr3_gen_second").
  **읽기 중 requestVoice 호출 금지.** (speak는 명세 §9상 채점 전 금지 → 읽기 중 낭독도 하지 마라.)
- diligence는 어른용 전용이므로 아동 화면에 점수/밴드 절대 표시 금지.

### Phase.Quiz (§5, §6 끝까지 풀고 — 정오 비공개)
- reefs를 **순서대로** 하나씩. 해당 문장을 보여주고, anchor 토큰 **바로 위 작은 팝업**(큰 모달 금지).
  팝업 위치는 Text의 onTextLayout + `layout.getBoundingBox(anchorStart)` 로 토큰 위 좌표를 잡아
  그 위에 Popup/Box를 띄운다(passage_read2의 TextLayoutResult.getBoundingBox 패턴 참고). 화면 이탈 없음.
- nametag: "사람 / 장소 / 특별한 이름" 3버튼 → 선택 시 firstChoiceLabel 저장, **즉시 닫고 다음 reef.** 정오 표시 안 함.
- detective: 빈칸문장 + 보기3개 칩 → 선택 저장, 즉시 다음. 정답/힌트 비노출.
- skeleton: anchor 위가 아니라 문장 **위쪽 질문 보드**(인라인, 원문은 접히지 않고 그대로 아래 유지).
  D0 질문("who?","did what?")부터 → 후보 칩 = **같은 depth 청크만** → 탭하면 그 청크가 슬롯에 박힘.
  그 다음 D1("which people?","what idea?") → 후보 = D1 청크만. attachTo로 해당 D0 답 아래 들여쓰기 표시.
  모든 질문 채우면 그 reef 완료. 중첩(D2)/접기 없음. firstChoice = 질문별 선택 청크 기록.
- 모든 reef 1차 시도 끝나면 **분기 선택 화면**: [부모와 함께] / [혼자 리뷰].

### Phase.Review (§6 부모 / §8 혼자)
- 혼자 리뷰: reef를 하나씩 돌며 **정오 공개**(맞음/틀림). 틀린 reef는 payload의 hint/meaning/reassure로
  **피드백 카드** 표시(명세 §8: childChoice·why). 오답 피드백을 ReefRunState에 연결.
  명세 §8.2의 "오답 웹뷰 전송"은 배선 불가 → 사전 공급된 hint/meaning을 피드백으로 사용(주석으로 명시).
- 부모 동반: 아동 화면은 "다시 골라볼까?" 정도의 낮은 부담 안내만. 정답·해설·firstChoice·정오의
  상세 패널은 **MasterView(어른용)** 가 소유(§10). 아동 화면엔 점수/정답 들이밀지 마라.
- 리뷰 완료 시: `session.complete(StepResult.Scored(selected=요약, answer=요약, score=correctN, maxScore=reefN))`.
  완독 직후(Review 진입 전)에는 §9대로 "방금 읽은 부분 보이스 복습"을 `requestVoice(READ_ALONG, payload=문장)`로
  한 번 깔아 로딩을 가린다(이 시점은 채점 후이므로 허용).

## 5. 영속/복원 (§7)
- 진입 시 `session.savedResult.collectAsState()` 읽어 Scored가 있으면 "이전 기록 있음" 상태로 복원
  (간단히: 리뷰 요약/점수를 보여주고 다시하기 버튼). 풀이 상태 전체 직렬화는 불필요 — Scored 요약 + trace로 충분.
- 각 단계 전이/선택을 trace로 남겨 MasterView가 재구성.

## 6. MasterView (어른용, §10)
- `DummyMasterScaffold(title="암초독해 — 기록", resultLines=trace.resultSummaries(),
   activities=trace.timeOrderedActivities().map { MasterActivityRow(formatClock(it.timeMillis), it.action, it.detail, it.detail) })`
  로 시작(passage_read2 MasterView와 동일 패턴). 여기에 reefDensity/난이도 요약 한 줄을 덧붙여도 좋다.

## 7. @Preview (필수 — 단독 렌더 검증)
```kotlin
@androidx.compose.ui.tooling.preview.Preview(name="phone", device="spec:width=411dp,height=891dp")
@androidx.compose.ui.tooling.preview.Preview(name="tablet", device="spec:width=1280dp,height=800dp,dpi=240")
@Composable private fun PassageRead3Preview() {
  PassageRead3Feature().StudentScreen(
    spec = PassageRead3Spec("preview", previewData()), session = FakeStepSession(), modifier = Modifier)
}
```
previewData()는 §2 예시 3문장(nametag/detective/skeleton 각 1개 이상)을 코드로 구성.

## 8. 검증 체크리스트(부록B — 구현이 만족해야 함)
- [ ] 읽기 구간에서 requestVoice/speak(원격·낭독) 호출 0
- [ ] 암초 정답이 Quiz 단계에서 노출되지 않음(firstChoice만 저장)
- [ ] 모든 풀이가 anchor 토큰 위 인라인(큰 모달 없음). skeleton은 원문 유지한 채 위쪽 질문보드
- [ ] skeleton 후보군이 같은 depth 청크로만 한정, D0→D1 순서
- [ ] 완독 후에만 보이스 복습 / 리뷰에서만 정오·피드백 공개
- [ ] 아동 화면에 점수/diligence/정답 미표시 — 어른용(MasterView)만
- [ ] @Preview 2종 + Hilt 바인딩 + parseSpec 예외처리
