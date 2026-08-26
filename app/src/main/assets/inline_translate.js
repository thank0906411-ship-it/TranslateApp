(function () {
  // 이미 이 페이지에 주입되어 있으면 중복 실행하지 않는다.
  if (window.__translateAppInjected) return;
  window.__translateAppInjected = true;

  // 문단/문장이 통째로 담기는 블록 레벨 요소만 번역 단위로 삼는다.
  // 텍스트 노드 하나하나를 따로 번역하면 <b>, <a> 등으로 쪼개진 조각이 각각
  // 문맥 없이 번역되어 "한 문장에 다른 언어가 섞이는" 현상이 생기므로,
  // 블록 전체의 textContent를 하나로 묶어 번역기에 보낸다.
  var BLOCK_SELECTOR = 'p, li, h1, h2, h3, h4, h5, h6, td, th, blockquote, dd, dt, figcaption, caption';

  function hasMeaningfulText(el) {
    var text = el.textContent.trim();
    return text.length >= 2;
  }

  // 블록 안에 또 다른 블록 요소가 중첩된 경우(예: <li><p>...</p></li>)
  // 안쪽 블록만 번역 단위로 쓰고 바깥 블록은 건너뛴다 (중복 번역 방지).
  function isInnermostBlock(el) {
    return el.querySelector(BLOCK_SELECTOR) === null;
  }

  function collectBlocks(root) {
    var blocks = [];
    var index = 0;
    window.__tappBlockRefs = {};

    root.querySelectorAll(BLOCK_SELECTOR).forEach(function (el) {
      if (el.closest('[contenteditable="true"]')) return;
      if (!hasMeaningfulText(el)) return;
      if (!isInnermostBlock(el)) return;

      var id = 'tapp-' + index;
      el.setAttribute('data-tapp-id', id);
      window.__tappBlockRefs[id] = el;
      blocks.push({ id: id, text: el.textContent.trim() });
      index++;
    });

    return blocks;
  }

  // Kotlin(JavascriptInterface)에게 수집된 블록 텍스트를 JSON으로 전달한다.
  window.tappCollectAndSend = function () {
    var blocks = collectBlocks(document.body);
    if (window.TranslateAppBridge) {
      window.TranslateAppBridge.onTextsCollected(JSON.stringify(blocks));
    }
  };

  // Kotlin이 번역 완료 후 { "tapp-0": "번역문", ... } 형태의 JSON을 넘기면
  // 해당 블록의 textContent 전체를 번역문으로 치환한다 (내부 태그 구조는 사라짐).
  window.tappApplyTranslations = function (mapJson) {
    var map = JSON.parse(mapJson);
    var refs = window.__tappBlockRefs || {};
    Object.keys(map).forEach(function (id) {
      var el = refs[id];
      if (el) {
        el.textContent = map[id];
      }
    });
  };

  window.tappCollectAndSend();
})();
