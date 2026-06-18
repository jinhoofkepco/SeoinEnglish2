# 지문 콘텐츠 흐름 정리

이 문서는 `passage_read` 지문 단원이 저작 도구에서 생성되어 JSON에 들어가고, 앱에서 아이에게 어떻게 보이는지 정리한 것이다.

## 관련 코드

- 저작 도구: `feature/master/src/main/kotlin/com/seoin/emojienglish/master/AuthoringViewModel.kt`
- 지문 화면: `steps/passageread/src/main/kotlin/com/seoin/emojienglish/steps/passageread/PassageReadFeature.kt`
- 단원 모델: `core/model/src/main/kotlin/com/seoin/emojienglish/model/Content.kt`
- 저장 위치: `core/content/src/main/kotlin/com/seoin/emojienglish/content/ContentWriter.kt`
- 샘플 단원: `app/src/main/assets/books/book_a/units/ant3_234687.json`

## 큰 흐름

1. 사용자가 지문 입력을 준비한다.
   - 지문 사진을 고르거나, 직접 텍스트를 넣는다.
   - 사진은 일반 선택(`지`) 또는 범위 선택(`지범`)으로 누적된다.
   - 단원명은 `unitTitle`로 입력한다. `unitId`가 비어 있으면 자동 생성된다.

2. 앱이 지문 문장 목록을 만든다.
   - 사진 입력이면 ChatGPT에 사진을 5장 단위로 첨부한다.
   - 각 사진 묶음에서 아이가 읽을 영어 지문 문장만 추출한다.
   - 문제 지시문, 한국어 설명, 번호, 선택지, 빈칸 등은 제외하도록 요청한다.
   - 여러 묶음에서 나온 페이지별 문장은 다시 GPT로 최종 읽기 순서 배열로 정리한다.
   - 텍스트 입력이면 텍스트에서 영어 문장 배열만 바로 추출한다.

3. 문장 목록으로 `passage_read`의 `params` JSON을 만든다.
   - JSON 생성 전 ChatGPT 모델을 현재 임시 설정인 `높음`으로 바꾼다.
   - 짧은 지문 경로는 `passagePrompt()`로 한 번에 JSON 객체를 받는다.
   - 긴 지문 경로는 `passageDownloadPrompt()`로 여러 part를 순차 처리한다.
   - 긴 지문은 현재 12문장 또는 약 3,800자 단위로 나뉜다.
   - part 2 이후에는 이전 JSON에 새 문장을 추가한 “전체 업데이트 JSON”을 다시 받도록 요청한다.

4. `params` JSON을 단원 JSON 안에 넣는다.
   - `LessonUnit.content`는 비워 둔다.
   - `steps[]`에 `{ "id": "s_passage", "type": "passage_read", "params": ... }` 형태로 들어간다.
   - `passage_read`는 공유 `content.passage`가 아니라 자기 `params` 안에 필요한 데이터를 모두 담는 자급자족 구조다.

5. 저장되면 책 목록에 반영된다.
   - 런타임 생성 단원은 `filesDir/books/<bookId>/units/<unitId>.json`에 저장된다.
   - `filesDir/books/<bookId>/book.json`의 `units[]`에도 단원 참조가 추가된다.
   - 번들 자산 단원은 `app/src/main/assets/books/<bookId>/units/*.json`에 있다.

현재 주의점: `PASSAGE_JSON_USE_DOWNLOAD_FLOW = true`라서, 긴 지문은 ChatGPT의 다운로드 파일을 확인한 뒤 그 최종 JSON을 다시 읽어 `passage_read` 단원으로 저장한다. `생성` 흐름에서는 이어서 단어/스토리/그리드 생성까지 계속 진행하고, `지저` 흐름에서는 `passage_read` 단원 저장까지 진행한다.

## 단원 JSON 구조

지문 단원은 대략 아래 형태다.

```json
{
  "schemaVersion": 2,
  "unitId": "ant3_234687",
  "title": "Ant City",
  "level": "A2",
  "content": {},
  "steps": [
    {
      "id": "s_passage",
      "type": "passage_read",
      "params": {
        "title": "Ant City",
        "trackLabel": "Shared Read",
        "curiosityQuestion": "What do you think is hidden inside an anthill?",
        "coverVisual": "🐜",
        "defaultChunkSetId": "short",
        "processSteps": [],
        "exploreItems": [],
        "paragraphs": []
      }
    }
  ]
}
```

`params`가 실제 지문 학습의 본체다.

## `params` 최상위 값

| 키 | 역할 | 입력/생성 방식 |
| --- | --- | --- |
| `title` | 표지와 단원 제목에 쓰는 영어 제목 | 단원명 힌트를 바탕으로 GPT 생성 |
| `trackLabel` | 표지 상단 작은 라벨 | 보통 `"Shared Read"` |
| `curiosityQuestion` | 표지의 호기심 질문 | GPT가 아이용 질문 1개 생성 |
| `coverVisual` | 표지 오른쪽 큰 시각 요소 | 이모지 또는 짧은 시각 표현 |
| `defaultChunkSetId` | 구형 `chunkSets` fallback용 기본 id | 현재는 `"short"` |
| `processSteps` | 표지의 “한눈에 보는 과정” 카드들 | GPT가 2~4개 정도 생성 |
| `exploreItems` | 지문 속 탐험 대상 목록 | GPT가 핵심 소재/개념을 생성 |
| `paragraphs` | 실제 지문 문단, 문장, 청크 | 추출 문장 기반으로 GPT 생성 |

## 문단 구조

`paragraphs[]`는 책처럼 읽히는 지문 단락이다.

```json
{
  "id": "p1",
  "title": "Inside an Anthill",
  "overlookQuestion": "What can we learn about the inside of an anthill?",
  "overlookHintKo": "개미집 안의 공간과 역할을 떠올려 보세요.",
  "sentences": []
}
```

- `title`: 읽기 화면 상단과 문단 헤더에 사용된다.
- `overlookQuestion`: 문단 끝의 `🔭` 표시를 누르면 하단에 펼쳐지는 돌아보기 질문이다.
- `overlookHintKo`: 돌아보기 질문 아래에 짧게 보이는 한국어 힌트다.
- `sentences`: 해당 문단에 들어가는 문장 목록이다.

문장이 5개 이하면 1문단, 많으면 의미 흐름에 따라 2~3문단 이상으로 나누도록 프롬프트가 되어 있다. 긴 지문 생성에서는 part별로 문단이 늘어날 수 있다.

## 문장 구조

각 문장은 원문 텍스트와 청크 목록을 가진다.

```json
{
  "id": "s1",
  "text": "Have you seen an anthill?",
  "chunks": []
}
```

- `id`: `s1`, `s2`처럼 전체 지문 순서대로 붙인다.
- `text`: 추출된 원문 문장이다. GPT에게 “글자 하나도 바꾸지 말 것”으로 요청한다.
- `chunks`: 아이가 문장을 덩어리로 해독할 수 있도록 나눈 2~5개 청크다.

문장 `text`는 매우 중요하다. 청크의 `startChar/endChar`가 이 문자열을 기준으로 검증되기 때문에 공백, 쉼표, 따옴표가 바뀌면 파싱 오류가 날 수 있다.

## 청크 구조

```json
{
  "id": "s1_c1",
  "text": "Have you seen",
  "startChar": 0,
  "endChar": 13,
  "role": "action",
  "decodeHint": "Have는 /hæv/, seen은 긴 e 소리로 읽어요.",
  "meaningKo": "본 적 있나요",
  "exploreIds": []
}
```

| 키 | 역할 |
| --- | --- |
| `id` | 청크 id. 보통 `s1_c1` 형식 |
| `text` | 청크로 보여줄 원문 일부 |
| `startChar` | 문장 문자열의 시작 인덱스, 0-based |
| `endChar` | 문장 문자열의 끝 인덱스, Kotlin `substring(start, end)` 기준 |
| `role` | 청크의 문장 역할. `subject/action/object/time/place/linker` 중심 |
| `decodeHint` | 아이가 청크를 읽고 해독할 때 볼 짧은 한국어 힌트 |
| `meaningKo` | 하단 청크 힌트에 쓰는 청크 단위 뜻. 현재 한글 보이스 payload에는 직접 들어가지 않지만, 넣는 방향이 좋다. |
| `exploreIds` | 관련 `exploreItems[].id` 목록. 없으면 빈 배열 |

검증 규칙은 강하다.

```kotlin
sentenceText.substring(startChar, endChar) == chunk.text
```

이 조건이 깨지면 `passage_read` 파싱이 실패한다. 따라서 JSON 후처리에서 가장 먼저 확인해야 할 값은 문장 원문과 청크 좌표다.

## 탐험 항목 구조

`exploreItems[]`는 지문 속에서 더 이야기하거나 그림으로 확장할 수 있는 소재다.

```json
{
  "id": "item1",
  "label": "anthill",
  "sentenceIds": ["s1", "s3"],
  "searchContext": "ant hill, ant nest entrance, dirt mound",
  "parentPrompt": "개미 언덕 안에는 무엇이 숨겨져 있을까?",
  "visual": "🏠"
}
```

- `label`: 탐험 대상 이름.
- `sentenceIds`: 이 소재가 등장하거나 관련 있는 문장 id.
- `searchContext`: 그림/탐험/검색성 프롬프트에 넣을 영어 맥락.
- `parentPrompt`: 보호자 또는 보이스 대화용 질문.
- `visual`: 이모지/시각 힌트.

현재 화면에서는 청크에 `exploreIds`가 있으면 해당 청크가 청크 색상 우선권을 받는다. 길게 눌러 텍스트를 선택하면 `그림` 액션으로 연결할 수 있다.

## 표지에서 아이에게 보이는 것

처음 진입하면 바로 지문 본문이 아니라 표지가 나온다.

- 상단 작은 pill: `trackLabel`
- 큰 제목: `title`
- 오른쪽 큰 시각 요소: `coverVisual`
- 호기심 카드: `curiosityQuestion`
- 과정 카드: `processSteps[]`
  - 각 카드에는 `visual`, 번호, `label`, `caption`이 보인다.
- 시작 버튼: `읽기 시작`

즉 `title`, `curiosityQuestion`, `coverVisual`, `processSteps`는 학습 데이터라기보다 첫 진입의 기대감과 읽기 방향을 잡는 화면 데이터다.

## 읽기 화면에서 아이에게 보이는 것

읽기 시작 후 화면은 크게 위아래로 나뉜다.

상단 지문 영역:

- 전체 문단이 책처럼 스크롤된다.
- 현재 문장은 화면 중앙으로 자동 스크롤된다.
- 현재 문장만 왼쪽 컬러 바와 진한 글자색으로 강조된다.
- 현재 문장의 청크들은 색상과 밑줄로 표시된다.
- 다른 문장은 흐린 색으로 남아 맥락을 유지한다.
- 문단 제목은 중간중간 헤더로 보인다.
- 문단 끝에는 작은 `🔭` 표시가 있고, 누르면 문단 돌아보기 질문이 하단에 펼쳐진다.

상호작용:

- 현재 문장을 볼 때 자동으로 `session.speak(current.text)`가 호출되어 문장이 낭독된다.
- 청크를 짧게 누르면 해당 청크 텍스트를 낭독한다.
- 현재 문장을 길게 누르면 단어가 선택되고, 누른 채 드래그하면 선택 범위가 확장된다.
- 선택 후 뜨는 작은 메뉴에서 `그림`을 누르면 선택 텍스트 기반 그림 요청으로 이어진다.
- 줄 끝 여백을 길게 누르면 문장 전체가 선택된다.

하단 컨트롤 영역:

- 선택한 청크가 있으면 청크 힌트가 나온다.
  - `chunk.text`
  - `chunk.meaningKo`
  - 다시 듣기 `🔊`
- 문단 `🔭`를 열면 문단 질문과 `overlookHintKo`가 나온다.
- 원형 버튼:
  - `이전`
  - `따라 읽기`
  - `EN`: 문장 구조 영어 설명
  - `한`: 청크 단위 영어/한글 설명
  - `다음` 또는 마지막 문장에서는 `완료`

## 보이스 모드로 넘어가는 콘텐츠 흐름

지문 화면에는 보이스가 두 종류 있다.

첫째는 즉시 낭독이다. 이건 ChatGPT 보이스 프롬프트가 아니라 기기 TTS에 가까운 빠른 읽기다.

- 문장이 화면의 현재 문장이 되면 `current.text`를 바로 읽는다.
- 현재 문장의 청크를 짧게 누르면 `chunk.text`만 읽는다.
- 하단 청크 힌트의 `🔊`를 누르면 다시 `chunk.text`를 읽는다.

둘째는 보이스 모드 프롬프트다. 이건 아이에게 설명하거나 질문하기 위해 `VoicePrompt`로 넘어간다. 콘텐츠 관점에서는 `passage_read` JSON의 문장/청크/문단 값이 보이스 선생님에게 전달되는 것이다.

| 아이가 누르는 것 | 보이스 목적 | 쓰는 콘텐츠 값 | 실제 payload 성격 |
| --- | --- | --- | --- |
| `따라 읽기` | 문장을 천천히 한 번 읽어 주기 | `sentence.text` | 원문 문장 그대로 |
| `EN` | 문장을 영어 청크 단위로 쉽게 설명 | `sentence.text`, `chunks[].text` | 청크 목록 + 원문 문장 |
| `한` | 문장을 영어/한글 청크 단위로 해석 | 현재는 `sentence.text` 중심 | 원문 문장 + “끊어 읽기 한국어 해석” 요청 |
| 문단 `🔭`의 `🎙` | 문단 내용 확인 질문 | 문단의 `overlookQuestion`, 문단 안 `sentences[].text` | 문단 전체 본문 + 예시 질문 |
| 탐험/확장 경로 | 소재 확장 대화 | `exploreItem.label`, `parentPrompt`, `searchContext` 계열 | 영상 검색어, 질문, 집 활동 요청 |

현재 `templateId`는 기록/구분 이름에 가깝고, 말하기 성격은 `kind`가 정한다.

- `passage_read_along`: `READ_ALONG`
- `passage_sentence_decode`: `EXPLAIN`
- `passage_sentence_ko`: `EXPLAIN`
- `passage_overlook`: `QUIZ_CONTEXT`
- `parent_explore_video/questions/home_experiment`: `FREE_TALK`

보이스 시스템은 모든 프롬프트 앞에 “천천히, 쉬운 영어로, 짧고 따뜻하게 말하라”는 공통 지시를 붙인다. 따라서 콘텐츠 JSON에는 긴 말투 지시를 넣기보다, 무엇을 설명할지 정확히 알 수 있는 짧은 재료를 넣는 편이 좋다.

## 보이스 프롬프트별 콘텐츠 설계

### 1. 자동 문장 낭독

현재 문장으로 이동할 때 바로 읽히는 값:

```text
sentence.text
```

콘텐츠 요구:

- 원문 문장이 아이가 듣기에 너무 길면 화면도 길고 낭독도 길어진다.
- 사진 추출 단계에서 문제 지시문이나 선택지가 섞이면 아이에게 그대로 읽힌다.
- 따라서 `sentence.text`는 “아이에게 읽힐 완성 문장”이어야 한다.

### 2. 청크 탭 낭독

청크를 누르면 읽히는 값:

```text
chunk.text
```

콘텐츠 요구:

- 청크는 소리 내어 따라 하기 좋은 길이여야 한다.
- 너무 긴 청크는 청크 학습의 의미가 줄고, 너무 짧은 청크는 뜻이 끊긴다.
- 현재 프롬프트 기준은 한 문장당 2~5개 청크다.

### 3. 따라 읽기

버튼: `따라 읽기`

보이스 payload:

```text
sentence.text
```

보이스 쪽 지시 성격:

```text
Read this aloud once for the child to repeat.
```

콘텐츠 요구:

- `sentence.text`가 원문 그대로여야 한다.
- 따라 읽기용 별도 해석문은 필요 없다.
- 문장 자체가 너무 길면, 아이는 청크가 아니라 긴 문장 전체를 한 번에 듣게 된다.

### 4. EN 설명

버튼: `EN`

보이스 payload는 대략 아래 형태로 만들어진다.

```text
이 문장을 청크 단위로 "영어 청크 → 그 청크를 아주 쉬운 영어 단어로 자세히 설명" 순서로 번갈아 말해줘
(청크: chunk1 / chunk2 / chunk3): sentence.text
```

쓰는 콘텐츠 값:

- `sentence.text`
- `chunks[]`를 `startChar` 순서로 정렬한 `chunk.text`

콘텐츠 요구:

- `chunk.text`가 문장 안에서 자연스러운 의미 단위여야 한다.
- `role`은 현재 payload에 직접 들어가지는 않지만, 청크 설계 품질을 잡는 데 중요하다.
- `decodeHint`도 현재 EN payload에는 직접 들어가지 않는다. 화면 힌트 또는 향후 보이스 개선 재료로 볼 수 있다.

EN 설명 품질을 올리려면 JSON에서 가장 중요한 것은 `chunks[].text`다. 보이스는 청크 목록을 보고 “이 청크는 쉬운 영어로 무엇인가”를 설명한다.

### 5. 한글 뜻

버튼: `한`

현재 보이스 payload:

```text
이 문장을 끊어 읽기로 한국어 해석해줘
(예: "the cream, 그 크림은, is beaten, 쳐져요"): sentence.text
```

쓰는 콘텐츠 값:

- 현재 코드상 payload에는 `sentence.text`가 중심으로 들어간다.
- `chunk.meaningKo`는 화면의 청크 힌트에는 쓰이지만, 이 보이스 payload에는 아직 직접 들어가지 않는다.

콘텐츠 관점 주의:

- 지금 구조에서는 보이스가 직접 문장을 다시 청크별로 해석한다.
- JSON의 `meaningKo`를 더 강하게 활용하려면, 향후 payload를 `chunk.text -> meaningKo` 목록으로 바꾸는 것이 좋다.
- 그래도 현재 화면 하단에는 선택한 청크의 `meaningKo`가 보이므로, `meaningKo`는 계속 잘 채워야 한다.

권장 payload 개선안:

```text
아래 청크 순서대로 영어를 읽고 한국어 뜻을 짧게 말해줘:
1. Have you seen = 본 적 있나요
2. an anthill = 개미집을
원문: Have you seen an anthill?
```

이렇게 바꾸면 GPT가 다시 해석하다가 원문과 어긋날 가능성이 줄어든다.

### 6. 문단 돌아보기

버튼: 문단 끝 `🔭`를 연 뒤 `🎙`

보이스 payload:

```text
방금 읽은 문단 내용을 아이에게 물어봐줘
(예: overlookQuestion). 문단: sentence1 sentence2 sentence3 ...
```

쓰는 콘텐츠 값:

- `paragraph.overlookQuestion`
- 해당 문단의 모든 `sentences[].text`
- 화면에는 `paragraph.overlookHintKo`도 같이 보인다.

콘텐츠 요구:

- `overlookQuestion`은 정답 암기형보다 문단 의미를 돌아보는 질문이어야 한다.
- 문단에 들어간 문장들이 질문 하나로 묶일 수 있어야 한다.
- `overlookHintKo`는 아이가 막혔을 때 보는 짧은 힌트로, 정답 전체를 다 말하지 않는 편이 좋다.

### 7. 탐험/확장 대화

현재 코드에는 탐험 항목용 payload 함수가 준비되어 있다. 다만 현재 화면에서 아이가 가장 직접 쓰는 확장 동작은 텍스트 선택 후 `그림` 요청이고, 아래 탐험 보이스 경로는 `exploreItems`를 더 적극적으로 연결할 때 쓰기 좋은 설계 재료다.

```text
"label"를 더 알아볼 어린이 영상 검색어를 한국어로 추천해줘
"label"에 대해 부모와 아이가 나눌 질문 몇 개를 한국어로 줘
"label"와 관련해 집에서 해볼 간단한 활동을 한국어로 알려줘
```

쓰는 콘텐츠 값:

- `exploreItem.label`
- 현재 문장 맥락
- 향후에는 `searchContext`, `parentPrompt`, `visual`을 더 적극적으로 넣을 수 있다.

콘텐츠 요구:

- `label`은 아이가 실제로 더 알아볼 만한 명사/개념이어야 한다.
- 너무 일반적인 단어보다 지문을 이해하는 데 도움이 되는 소재가 좋다.
- 예: `anthill`, `harvester ants`, `tunnels and rooms`

현재 `parentPrompt`와 `searchContext`는 JSON에 들어가지만, 보이스 payload에 직접 충분히 쓰이지 않는다. 콘텐츠 설계상으로는 좋은 값이므로, 다음 개선에서 parent/explore payload에 포함시키는 것이 좋다.

## 그림 요청에 쓰이는 값

그림 요청은 보이스 모드가 아니라 그림 쪽으로 가는 프롬프트다. 아이가 현재 문장에서 텍스트를 길게 선택하고 `그림`을 누르면 아래 형태로 요청한다.

```text
"selectedText" 의 사진이나 그림을 보여줘. 못 찾으면 아이가 알아볼 수 있게 간단히 그려줘. 문맥: sentence.text
```

쓰는 콘텐츠 값:

- 아이가 선택한 텍스트
- 현재 `sentence.text`

콘텐츠 요구:

- 선택할 만한 텍스트가 문장 안에 자연스럽게 있어야 한다.
- 탐험 소재는 `exploreItems`와 연결되어 있으면 나중에 그림/영상/질문 확장에 재사용하기 좋다.

## 추가로 입력하거나 보강해야 하는 값

저작자가 직접 입력하는 값:

- `unitTitle`: 단원 제목 힌트. 비워두면 자동 id가 제목처럼 쓰일 수 있다.
- 지문 사진 또는 지문 텍스트.
- 필요하면 고급 영역의 `bookTitle`, `unitId`.

GPT가 원문 외에 만들어야 하는 값:

- 표지용 `title`, `curiosityQuestion`, `coverVisual`.
- 읽기 전략 카드인 `processSteps`.
- 문단 나눔과 문단별 `title`, `overlookQuestion`, `overlookHintKo`.
- 문장별 청크 나눔.
- 청크별 `role`, `decodeHint`, `meaningKo`.
- 탐험 소재 `exploreItems`, 그리고 청크의 `exploreIds` 연결.

후처리/검수자가 반드시 확인해야 하는 값:

- 모든 `sentence.text`가 원문과 정확히 같은지.
- 모든 청크의 `startChar/endChar`가 Kotlin substring 규칙과 맞는지.
- 한 문장에 청크가 2~5개 정도인지.
- `meaningKo`가 너무 길거나 문장 전체 번역처럼 되지 않았는지.
- `exploreIds`가 실제 존재하는 `exploreItems[].id`만 가리키는지.
- 문단 수가 너무 많거나 너무 적지 않은지.

## 실패 시 fallback

`passage_read` 파서는 어느 정도 fallback을 가진다.

- `paragraphs`가 없고 `params.sentences`가 있으면 단일 문단으로 읽는다.
- 청크가 없으면 문장 전체를 하나의 청크로 만든다.
- `title`, `trackLabel`, `coverVisual` 등이 없으면 기본값을 쓴다.

하지만 rich 지문 단원 품질은 `paragraphs -> sentences -> chunks` 구조가 잘 들어갔을 때 가장 좋다. 특히 청크 좌표가 틀리면 fallback이 아니라 파싱 오류가 날 수 있으므로 좌표 검증은 필수다.
