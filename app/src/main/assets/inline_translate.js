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

  // 이미 번역 요청을 보냈거나 번역 완료된 블록은 data-tapp-id로 표시해두고 다시 건드리지 않는다.
  window.__tappBlockRefs = window.__tappBlockRefs || {};
  window.__tappNextIndex = window.__tappNextIndex || 0;

  function collectBlocks(root) {
    var blocks = [];

    root.querySelectorAll(BLOCK_SELECTOR).forEach(function (el) {
      if (el.hasAttribute('data-tapp-id')) return;
      if (el.closest('[contenteditable="true"]')) return;
      if (!hasMeaningfulText(el)) return;
      if (!isInnermostBlock(el)) return;

      var id = 'tapp-' + window.__tappNextIndex++;
      el.setAttribute('data-tapp-id', id);
      window.__tappBlockRefs[id] = el;
      blocks.push({ id: id, text: el.textContent.trim() });
    });

    return blocks;
  }

  // 최초 로드 시 번역 전 원문이 잠깐 보였다 바뀌는 깜빡임을 줄이기 위해, 번역이 끝날 때까지
  // 살짝 흐리게 표시한다(완전히 숨기면 로딩이 멈춘 것처럼 보이므로 opacity만 낮춘다).
  var fadeStyle = document.createElement('style');
  fadeStyle.setAttribute('data-tapp-fade', '1');
  fadeStyle.textContent = 'body { opacity: 0.35; transition: opacity 0.25s ease-out; }';
  document.head.appendChild(fadeStyle);

  window.tappRevealPage = function () {
    var el = document.querySelector('style[data-tapp-fade]');
    if (el) el.remove();
  };
  // 번역할 블록이 애초에 없거나(이미지만 있는 페이지 등) 실패해도 화면이 계속 흐린 채로
  // 남지 않도록 안전장치로 최대 4초 후에는 무조건 원래대로 되돌린다.
  setTimeout(window.tappRevealPage, 4000);

  // Kotlin(JavascriptInterface)에게 수집된 블록 텍스트를 JSON으로 전달한다.
  // root를 생략하면 document.body 전체(최초 로드), 넘기면 그 하위(새로 추가된 부분)만 훑는다.
  window.tappCollectAndSend = function (root) {
    var blocks = collectBlocks(root || document.body);
    if (blocks.length > 0 && window.TranslateAppBridge) {
      window.TranslateAppBridge.onTextsCollected(JSON.stringify(blocks));
    } else if (!root) {
      // 최초 로드인데 번역할 블록이 없으면 바로 화면을 보여준다.
      window.tappRevealPage();
    }
  };

  // SPA/무한스크롤 사이트는 초기 로드 이후에도 JS가 계속 새 콘텐츠를 DOM에 추가한다.
  // MutationObserver로 새로 추가된 노드만 감지해 그 하위의 미번역 블록을 찾아 번역한다.
  // 스크롤/타이핑 중 수십 번씩 뮤테이션이 튈 수 있어 300ms 디바운스로 묶어서 처리한다.
  if (window.__tappObserver) {
    window.__tappObserver.disconnect();
  }
  var debounceTimer = null;
  var pendingRoots = [];
  window.__tappObserver = new MutationObserver(function (mutations) {
    mutations.forEach(function (m) {
      m.addedNodes.forEach(function (node) {
        if (node.nodeType === 1) pendingRoots.push(node);
      });
    });
    if (debounceTimer) clearTimeout(debounceTimer);
    debounceTimer = setTimeout(function () {
      var roots = pendingRoots;
      pendingRoots = [];
      roots.forEach(function (root) {
        if (root.isConnected) window.tappCollectAndSend(root);
      });
    }, 300);
  });
  window.__tappObserver.observe(document.body, { childList: true, subtree: true });

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
    window.tappRevealPage();
  };

  window.tappCollectAndSend();
})();
