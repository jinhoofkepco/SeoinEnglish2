# Emoji English — Android (skeleton)

이 저장소는 설계서 v1 + 부록 A(VoiceGateway)를 기준으로 한 **멀티모듈 골격**입니다.
이번 세션 범위: **메인 셸 + 더미 스텝 + 전체 윤곽**. 각 스텝의 실제 학습 화면은
다른 세션에서 개별 모듈만 교체해 채웁니다(설계 §0.2, §13).

## 무엇이 들어 있나 (이번 세션)

| 구분 | 모듈 | 상태 |
|---|---|---|
| 계약(동결 대상) | `feature:step-api` | ✅ StepFeature / StepSession / StepRegistry / FakeStepSession / SampleContent |
| 모델 | `core:model` | ✅ Book·Unit·Content·StepResult·Trace·Plan·PlayQueue·NavRoutes |
| 음성 | `core:voice` | ✅ 부록 A 계약(VoiceTurnScript·TurnOutcome·VoiceGateway) + Mock/Fake + VoiceController |
| 콘텐츠 | `core:content` | ✅ LessonRepository(assets) + ContentValidator |
| 데이터 | `core:data` | ✅ Trace/Progress/Plan/MasterMode 인터페이스 + **인메모리 구현**(M5에서 Room 교체) |
| 디자인 | `core:designsystem` | ✅ 테마·적응형 컬럼·공통 컴포넌트·**DummyStepScaffold** |
| 셸 | `feature:main` | ✅ AppShell + **전 화면 공통 얇은 하단바**(접힘/펴기 · 🎙Voice · 🔒마스터) + PIN 다이얼로그("마스터 모드 유지" 체크) + 글로벌 🎙 시트 |
| 홈 | `feature:home` | ✅ **통합 홈**(상단 1/3 오늘의 할일 한줄 리스트 + 하단 책 아이콘 그리드) + 책상세 |
| 플레이어 | `feature:player` | ✅ **상단 네비게이터 바**(제목 1/3 + 스텝 칩 스크롤 + 마스터 칩) + 떠있는 "다음 단계"(완료 시 노출) + 칩 이동(학생=완료분만) + 마스터 시 MasterView |
| 마스터 | `feature:master` | ◑ 중앙 대시보드 = **로그 한줄 리스트**(클릭→해당 스텝 활동화면) — 편집기/JSON 임포트는 M7 |
| 스텝 ×6 | `steps:*` | ◑ **더미**: 자기 params는 진짜로 파싱, 화면은 DummyStepScaffold로 표시 |
| 앱 | `app` | ✅ Hilt 조립 + NavHost + 샘플 책(assets) |

`◑` = 윤곽/배선 완료, 본 구현은 다른 세션.

## 실행 흐름 (데모)

홈 상단 "데모: 첫 단원 시작 ▶" → Player가 `book_a/u01_restaurant`의 6개 스텝(더미)을
재생. 상단 네비게이터 바에 스텝 칩이 보이고 현재 칩이 맨 앞. 스텝의 "완료" 버튼을
누르면 화면 하단에 "다음 단계"가 떠오름(완료 전엔 숨김). 제목을 누르면 홈으로.

**마스터**: 좌하단 ▲로 하단바를 펴고 🔒 마스터 → PIN `0000`(+"마스터 모드 유지"
체크하면 이후 PIN 없이 토글). 마스터 ON이면 각 스텝이 학생 활동(회차별 결과 +
시간순 활동 리스트)으로 바뀌고, 네비게이터 칩으로 전 스텝 자유 이동. 네비게이터
맨 끝 "마스터" 칩 → 중앙 로그 대시보드(줄 클릭 시 해당 스텝 활동화면으로 이동).

## 다음 세션이 스텝 하나를 구현하는 법

1. 해당 `steps/<name>/.../XxxFeature.kt`의 `StudentScreen`(과 필요 시 `parseSpec`,
   `MasterView`)만 실제 UI로 교체.
2. `StepSession`(complete/trace/requestVoice/speak)과 `StepResult`만 사용.
   화면 이동·다른 feature import 금지(§0.2, §0.3).
3. `FakeStepSession` + 폰/탭 2종 `@Preview`로 단독 개발(§12.1).
4. `step-api` 계약은 **수정 금지**(필요 시 중단·보고, §13).

## 빌드

- 툴체인: AGP 9.2.1 / Gradle 9.4.1 / Kotlin 2.2.10(빌트인) / compileSdk 36 / minSdk 26.
- AGP 9 **빌트인 Kotlin** 사용 → 모듈에 `kotlin-android` 플러그인을 적용하지 않습니다.
  KSP 공존을 위해 `gradle.properties`에 `android.disallowKotlinSourceSets=false`.
- `./gradlew :app:assembleDebug` (Android Studio JBR 권장).

## 아직 더미/임시인 부분 (의도된 TODO)

- `core:data` 인메모리 → Room(M5).
- `core:voice` MockVoiceGateway → WebView 실구현(M2′, 부록 A §G 이관).
- `templates.json`의 코칭 system 프롬프트 = `서인영어_brain2` 문서 수령 후 주입(코드 무수정, §14).
- 마스터 편집기/책 임포트/스텝 MasterView 드릴다운(M7).
- 11" 탭 List-Detail 2-pane(M6).
