(function () {
  // 이미 이 페이지에 주입되어 있으면 중복 실행하지 않는다.
  if (window.__translateAppInjected) return;
  window.__translateAppInjected = true;

  var SKIP_TAGS = { SCRIPT: 1, STYLE: 1, NOSCRIPT: 1, TEXTAREA: 1, INPUT: 1, SELECT: 1, TITLE: 1 };

  // 텍스트가 있는 노드를 찾아 순서를 부여하고, window.__tappNodeRefs에 실제 텍스트
  // 노드 참조를 담아둔다 (텍스트 노드는 속성을 가질 수 없으므로 id는 JS 프로퍼티로만 표시).
  function collectTextNodes(root) {
    var nodes = [];
    var walker = document.createTreeWalker(root, NodeFilter.SHOW_TEXT, {
      acceptNode: function (node) {
        var text = node.nodeValue.trim();
        if (text.length < 2) return NodeFilter.FILTER_REJECT;
        var parent = node.parentElement;
        if (!parent || SKIP_TAGS[parent.tagName]) return NodeFilter.FILTER_REJECT;
        if (parent.closest('[contenteditable="true"]')) return NodeFilter.FILTER_REJECT;
        return NodeFilter.FILTER_ACCEPT;
      }
    });

    var node;
    var index = 0;
    window.__tappNodeRefs = {};
    while ((node = walker.nextNode())) {
      var id = 'tapp-' + index;
      node.__tappId = id;
      window.__tappNodeRefs[id] = node;
      nodes.push({ id: id, text: node.nodeValue });
      index++;
    }
    return nodes;
  }

  // Kotlin(JavascriptInterface)에게 수집된 텍스트를 JSON으로 전달한다.
  window.tappCollectAndSend = function () {
    var nodes = collectTextNodes(document.body);
    if (window.TranslateAppBridge) {
      window.TranslateAppBridge.onTextsCollected(JSON.stringify(nodes));
    }
  };

  // Kotlin이 번역 완료 후 { "tapp-0": "번역문", ... } 형태의 JSON을 넘기면
  // 해당 텍스트 노드를 정확히 그 자리에서 치환한다.
  window.tappApplyTranslations = function (mapJson) {
    var map = JSON.parse(mapJson);
    var refs = window.__tappNodeRefs || {};
    Object.keys(map).forEach(function (id) {
      var node = refs[id];
      if (node) {
        node.nodeValue = map[id];
      }
    });
  };

  window.tappCollectAndSend();
})();
