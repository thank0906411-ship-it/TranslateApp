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

    // querySelectorAll은 root 자신은 검사하지 않고 자손만 훑는다. SPA가 새 콘텐츠를
    // DOM에 추가할 때 <p>새 문단</p>처럼 블록 요소 자체를 통째로 추가하는 경우가 흔한데,
    // 이 경우 root(=그 <p>)가 후보에서 빠져 번역되지 않는 문제가 있어 root 자신도 함께 검사한다.
    var candidates = root.matches && root.matches(BLOCK_SELECTOR)
      ? [root].concat(Array.prototype.slice.call(root.querySelectorAll(BLOCK_SELECTOR)))
      : Array.prototype.slice.call(root.querySelectorAll(BLOCK_SELECTOR));

    candidates.forEach(function (el) {
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

  // 페이지의 <html lang="..."> 속성으로 원문 언어를 추정해 Kotlin에 전달한다.
  // 정확도가 완벽하진 않지만(사이트가 lang을 안 쓰거나 틀리게 쓰는 경우도 있음),
  // 대부분의 사이트는 이 값을 정확히 채워두므로 "출발어 자동 감지"의 실용적인 신호가 된다.
  var htmlLang = document.documentElement.getAttribute('lang');
  if (htmlLang && window.TranslateAppBridge && window.TranslateAppBridge.onLanguageDetected) {
    window.TranslateAppBridge.onLanguageDetected(htmlLang);
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

  // 블록 안에 클릭 가능한 인터랙티브 요소(링크/버튼/입력 등)가 있는지 확인한다.
  // 있으면 el.textContent를 통째로 덮어써서는 안 된다 — textContent 대입은 해당
  // 엘리먼트의 모든 자식 노드를 지우고 텍스트 노드 하나로 바꿔버리므로, 블록 안에
  // 있던 <a>/<button> 자체가 통째로 사라져 클릭이 안 되는 문제가 생긴다(실제로
  // 어떤 사이트는 "다음 화" 링크가 문단 안에 있어서 번역 후 클릭이 안 됐던 원인).
  var INTERACTIVE_SELECTOR = 'a, button, input, select, textarea, [onclick], [role="button"]';

  function hasInteractiveDescendant(el) {
    return el.querySelector(INTERACTIVE_SELECTOR) !== null;
  }

  // 블록 안의 텍스트 노드를 문서 순서대로 모은다 (문단 안에 여러 텍스트 노드가
  // <a>/<b> 등으로 나뉘어 있을 수 있다).
  function collectTextNodesInOrder(root) {
    var result = [];
    var walker = document.createTreeWalker(root, NodeFilter.SHOW_TEXT, null);
    var node;
    while ((node = walker.nextNode())) {
      result.push(node);
    }
    return result;
  }

  // 번역문을 원문 텍스트 노드들의 "길이 비율"에 맞춰 대략적으로 나눠 배치한다.
  // 번역 API가 토큰 정렬(어느 원문 구절이 어느 번역 구절에 대응하는지) 정보를 주지
  // 않으므로 완벽한 대응은 불가능하지만, 각 노드가 원문에서 차지했던 비중만큼
  // 번역문의 글자 수를 배분하면 "번역문 전체가 한 곳에 몰리는" 것보다는 원문의
  // 대략적인 구조를 따라간다. 단어 중간이 아니라 공백 경계에서 끊어 어색함을 줄인다.
  function distributeByRatio(translatedText, originalLengths) {
    var totalOriginal = originalLengths.reduce(function (sum, len) { return sum + len; }, 0);
    if (totalOriginal === 0) return originalLengths.map(function () { return ''; });

    var parts = [];
    var remaining = translatedText;
    var consumedRatio = 0;

    for (var i = 0; i < originalLengths.length; i++) {
      var isLast = i === originalLengths.length - 1;
      consumedRatio += originalLengths[i] / totalOriginal;

      if (isLast) {
        parts.push(remaining);
        break;
      }

      var targetCut = Math.round(translatedText.length * consumedRatio) - (translatedText.length - remaining.length);
      targetCut = Math.max(0, Math.min(targetCut, remaining.length));

      // 단어 중간에서 끊기지 않도록 targetCut 근방에서 가장 가까운 공백을 찾는다.
      var cut = targetCut;
      var searchRadius = 10;
      for (var d = 0; d <= searchRadius; d++) {
        if (targetCut + d < remaining.length && remaining[targetCut + d] === ' ') { cut = targetCut + d; break; }
        if (targetCut - d >= 0 && remaining[targetCut - d] === ' ') { cut = targetCut - d; break; }
      }

      parts.push(remaining.slice(0, cut).trim());
      remaining = remaining.slice(cut);
    }

    return parts;
  }

  // 번역 완료된 블록을 길게 누르면(500ms) 원문을 Kotlin에 넘겨 Toast로 보여준다.
  // 인터랙티브 요소(링크/버튼 등)가 있는 블록은 제외한다 — 모바일 브라우저는 롱프레스를
  // 링크 컨텍스트 메뉴(새 탭에서 열기 등)로도 쓰므로, 여기서 리스너를 추가하면 그 기본
  // 동작과 충돌하거나 사용자가 의도치 않게 원문 팝업을 보게 될 수 있다.
  var LONG_PRESS_MS = 500;

  function attachOriginalTextLongPress(el, originalText) {
    if (hasInteractiveDescendant(el)) return;
    el.setAttribute('data-tapp-original', originalText);

    // 재시도(retryBlock) 성공 시 같은 블록에 tappApplyTranslations가 다시 호출될 수
    // 있다 — data-tapp-original 값은 새로 갱신해야 하지만(위에서 이미 함), 리스너는
    // 한 번만 달면 충분하므로 매번 새 클로저로 addEventListener가 중복 등록되는 것을
    // 이 플래그로 막는다(안 막으면 롱프레스 한 번에 Toast가 여러 번 뜨게 된다).
    if (el.hasAttribute('data-tapp-longpress-bound')) return;
    el.setAttribute('data-tapp-longpress-bound', '1');

    var pressTimer = null;
    var start = function () {
      pressTimer = setTimeout(function () {
        pressTimer = null;
        if (window.TranslateAppBridge && window.TranslateAppBridge.onShowOriginalText) {
          window.TranslateAppBridge.onShowOriginalText(el.getAttribute('data-tapp-original') || '');
        }
      }, LONG_PRESS_MS);
    };
    var cancel = function () {
      if (pressTimer) { clearTimeout(pressTimer); pressTimer = null; }
    };

    el.addEventListener('touchstart', start, { passive: true });
    el.addEventListener('touchend', cancel);
    el.addEventListener('touchmove', cancel);
    el.addEventListener('touchcancel', cancel);
    el.addEventListener('mousedown', start);
    el.addEventListener('mouseup', cancel);
    el.addEventListener('mouseleave', cancel);
  }

  // Kotlin이 번역 완료 후 { "tapp-0": "번역문", ... } 형태의 JSON을 넘기면 해당
  // 블록에 번역문을 적용한다. 인터랙티브 요소가 없는 블록은 기존처럼 textContent를
  // 통째로 치환하고(가장 단순하고 확실함), 인터랙티브 요소가 있는 블록은 자식 구조를
  // 보존하기 위해 텍스트 노드별 원문 길이 비율에 맞춰 번역문을 나눠 배치한다.
  window.tappApplyTranslations = function (mapJson) {
    var map = JSON.parse(mapJson);
    var refs = window.__tappBlockRefs || {};
    Object.keys(map).forEach(function (id) {
      var el = refs[id];
      if (!el) return;

      // 원문은 textContent를 치환하기 전(지금)만 읽을 수 있다 — 롱프레스 시점에는
      // 이미 번역문으로 바뀐 뒤라 다시 꺼낼 수 없으므로 지금 속성으로 저장해둔다.
      var originalText = el.textContent;
      attachOriginalTextLongPress(el, originalText);

      if (!hasInteractiveDescendant(el)) {
        el.textContent = map[id];
        return;
      }

      var textNodes = collectTextNodesInOrder(el);
      if (textNodes.length === 0) return;
      if (textNodes.length === 1) {
        textNodes[0].nodeValue = map[id];
        return;
      }

      var originalLengths = textNodes.map(function (n) { return n.nodeValue.length; });
      var parts = distributeByRatio(map[id], originalLengths);
      textNodes.forEach(function (textNode, index) {
        textNode.nodeValue = parts[index] || '';
      });
    });
    window.tappRevealPage();
  };

  // Kotlin이 "엔진 폴백까지 시도했는데도 원문과 사실상 동일했던" 블록 id 목록(및 각
  // 블록의 원문 텍스트)을 넘기면, 해당 블록에 점선 밑줄과 툴팁을 달아 사용자가 번역
  // 실패 가능성을 알아볼 수 있게 한다. 텍스트 자체를 건드리지 않으므로(스타일/속성만
  // 추가) 인터랙티브 요소 보존 로직과 충돌하지 않는다. 인터랙티브 요소(링크/버튼 등)가
  // 있는 블록은 탭 재시도 리스너를 달지 않는다 — 블록 전체에 클릭 리스너를 걸면 안의
  // 링크 클릭과 충돌해 사용자가 페이지를 못 넘어가게 될 수 있기 때문이다. 원문 텍스트는
  // el.textContent가 이미 번역문으로 치환된 뒤라 다시 꺼낼 수 없으므로, 실패 표시 시점에
  // data-tapp-original 속성으로 따로 저장해둔다(재시도할 때 Kotlin에 다시 보내야 함).
  var failStyle = document.createElement('style');
  failStyle.textContent =
    '.tapp-translate-failed { border-bottom: 1px dashed #e53935; }' +
    '.tapp-translate-retryable { cursor: pointer; }';
  document.head.appendChild(failStyle);

  window.tappMarkTranslationFailed = function (idsJson, originalsJson) {
    var ids = JSON.parse(idsJson);
    var originals = JSON.parse(originalsJson);
    var refs = window.__tappBlockRefs || {};
    ids.forEach(function (id) {
      var el = refs[id];
      if (!el) return;
      el.classList.add('tapp-translate-failed');

      if (hasInteractiveDescendant(el)) {
        el.setAttribute('title', '이 문장은 번역되지 않았을 수 있습니다.');
        return;
      }

      el.classList.add('tapp-translate-retryable');
      el.setAttribute('data-tapp-original', originals[id] || '');
      el.setAttribute('title', '이 문장은 번역되지 않았을 수 있습니다. 눌러서 다시 시도하세요.');
      el.addEventListener('click', function onRetryClick() {
        if (!window.TranslateAppBridge || !window.TranslateAppBridge.onRetryTranslation) return;
        var original = el.getAttribute('data-tapp-original');
        if (!original) return;
        // 재시도 중 중복 클릭을 막기 위해 리스너를 바로 제거한다. 재시도가 다시
        // 실패하면 Kotlin이 tappMarkTranslationFailed를 다시 호출해 리스너를 재등록한다.
        el.removeEventListener('click', onRetryClick);
        el.classList.remove('tapp-translate-retryable');
        window.TranslateAppBridge.onRetryTranslation(id, original);
      });
    });
  };

  window.tappCollectAndSend();
})();
