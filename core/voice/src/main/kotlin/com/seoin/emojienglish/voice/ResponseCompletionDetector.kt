package com.seoin.emojienglish.voice

import org.json.JSONObject

/**
 * ChatGPT 웹뷰에서 "어시스턴트 응답이 끝났는지"를 판단하는 **단일 출처**.
 *
 * 완료의 확실한 신호는 send 버튼 재등장이 아니라(답 끝나기 전에도 뜸),
 * 해당 어시스턴트 메시지 **하단 액션 툴바(복사/재생성/좋아요 등) 등장**이다.
 *
 * - [ACTION_MENU_JS] : 그 신호를 계산하는 JS 함수 1벌. 모든 스냅샷이 이걸 splice 해서 쓴다.
 *   (스냅샷 JSON 의 `assistantActionsReady` 가 이 함수 결과다.)
 * - [isComplete]     : 스냅샷으로부터 완료 여부를 정하는 Kotlin 결정 1벌. 모든 query/capture 가 이걸 쓴다.
 *
 * 게이트웨이마다 복붙하지 말 것 — 여기만 고친다.
 */
internal object ResponseCompletionDetector {

    /**
     * 자급자족(self-contained) JS 함수 선언. 바깥 스코프의 변수에 의존하지 않으며
     * 내부 식별자는 `_acm` 접두로 충돌을 피한다. 스냅샷 IIFE 안 어디에 splice 해도 안전.
     * 호출부는 그대로 `actionMenuReady(node)` 를 쓰면 된다.
     */
    const val ACTION_MENU_JS: String = """
        function actionMenuReady(root){
          if(!root) return false;
          var _acmVisible=function(el){ if(!el||!el.getBoundingClientRect) return false; var r=el.getBoundingClientRect(); var s=getComputedStyle(el); return r.width>0&&r.height>0&&s.visibility!=="hidden"&&s.display!=="none"; };
          var _acmLabel=function(el){ return [el&&el.innerText,el&&el.ariaLabel,el&&el.title,el&&el.getAttribute&&el.getAttribute("aria-label"),el&&el.getAttribute&&el.getAttribute("data-testid")].filter(Boolean).join(" "); };
          var _acmRect=root.getBoundingClientRect?root.getBoundingClientRect():{top:0,bottom:0,left:0,right:0};
          var _acmScope=root.closest&&(root.closest("[data-testid^='conversation-turn']")||root.closest("article")||root.parentElement);
          var _acmWords=/copy|copied|copy-turn-action-button|good response|good-response-turn-action-button|bad response|bad-response-turn-action-button|like|dislike|thumb|regenerate|share|edit|복사|좋은|나쁜|별로|좋아요|싫어요|공유|다시 생성|편집/i;
          var _acmButtons=Array.prototype.slice.call(document.querySelectorAll("button,[role='button']")).filter(_acmVisible);
          var _acmCandidates=[];
          if(_acmScope&&_acmScope.querySelectorAll){ _acmCandidates.push.apply(_acmCandidates, Array.prototype.slice.call(_acmScope.querySelectorAll("button,[role='button']"))); }
          _acmCandidates.push.apply(_acmCandidates, _acmButtons.filter(function(b){ var r=b.getBoundingClientRect(); return r.top>=_acmRect.top-24 && r.top<=_acmRect.bottom+220 && r.left>=0 && r.right<=window.innerWidth+4; }));
          return _acmCandidates.some(function(b){ return _acmWords.test(_acmLabel(b)); });
        }
    """

    /**
     * 완료 판정 단일 규칙.
     * - 1차 신호: 하단 액션 툴바 등장(assistantActionsReady).
     * - 안전망: 텍스트가 [stableMs] 이상 변화 없고 생성중지 버튼도 없을 때.
     * - 업로드 중이면 항상 미완료.
     */
    fun isComplete(
        snapshot: JSONObject,
        hasNew: Boolean,
        quietMs: Long,
        stableMs: Long,
    ): Boolean {
        if (!hasNew) return false
        if (snapshot.optBoolean("uploadingVisible")) return false
        val actionsReady = snapshot.optBoolean("assistantActionsReady")
        val stop = snapshot.optBoolean("stopVisible")
        return actionsReady || (quietMs >= stableMs && !stop)
    }
}
