
(function() {
    'use strict';

    function toast(msg) { hcj.notify((hcj.manifest && hcj.manifest.name) || 'plugin', String(msg)); }
    function pushPanel() { hcj.panel.send({ type: 'panel', html: getPanelHtml() }); }
    function pushHtml(html) { hcj.panel.send({ type: 'panel', html: html }); }


    const LANG = (hcj.lang || 'zh').toLowerCase().startsWith('ar') ? 'ar' :
                 (hcj.lang || 'zh').toLowerCase().startsWith('zh') ? 'zh' : 'en';
    const I18N = {
        zh: { name: '页内查找', placeholder: '在当前页面查找', prev: '上一个', next: '下一个', clear: '清除', noMatch: '未找到匹配项', nativeUnavailable: '当前内核不支持原生页内查找', enterKeyword: '请输入关键词', searching: '正在查找...', tip: '使用 WebView 原生查找，高亮结果并自动定位', matchCount: '{0} / {1}' },
        en: { name: 'Find in page', placeholder: 'Find in current page', prev: 'Previous', next: 'Next', clear: 'Clear', noMatch: 'No matches found', nativeUnavailable: 'Native find is unavailable in this engine', enterKeyword: 'Enter a keyword', searching: 'Searching...', tip: 'Uses native WebView search to highlight and jump between matches', matchCount: '{0} / {1}' },
        ar: { name: 'بحث في الصفحة', placeholder: 'ابحث في الصفحة الحالية', prev: 'السابق', next: 'التالي', clear: 'مسح', noMatch: 'لا توجد نتائج', nativeUnavailable: 'البحث الأصلي غير متاح في هذا المحرك', enterKeyword: 'أدخل كلمة البحث', searching: 'جاري البحث...', tip: 'يستخدم بحث WebView الأصلي لتمييز النتائج والتنقل بينها', matchCount: '{0} / {1}' }
    };
    const T = I18N[LANG] || I18N.en;

    let currentQuery = '';
    let currentState = { supported: false, activeMatchOrdinal: -1, numberOfMatches: 0, doneCounting: true, displayIndex: 0 };

    function esc(value) {
        return String(value || '').replace(/[&<>"']/g, function(ch) {
            return ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[ch];
        });
    }

    function parseState(raw) {
        if (!raw) return currentState;
        if (typeof raw === 'object') return raw;
        try { return JSON.parse(raw); } catch(e) { return currentState; }
    }

    function setState(nextState) {
        currentState = Object.assign({}, currentState, parseState(nextState));
        refreshPanel();
    }

    function callNative(name) {
        if (typeof NativeBridge === 'undefined' || typeof NativeBridge[name] !== 'function') return null;
        try {
            if (name === 'findInPage') return NativeBridge.findInPage(currentQuery);
            if (name === 'findNextInPage') return NativeBridge.findNextInPage(arguments[1] !== false);
            if (name === 'clearFindInPage') return NativeBridge.clearFindInPage();
        } catch(e) {
            console.warn('[Find in page] Native call failed:', e);
        }
        return null;
    }

    function fallbackFind(forward) {
        if (!currentQuery) return false;
        try {
            if (typeof window.find === 'function') {
                return window.find(currentQuery, false, forward === false, true, false, false, false);
            }
        } catch(e) {
            console.warn('[Find in page] window.find fallback failed:', e);
        }
        return false;
    }

    function doSearch(q) {
        if (typeof q === 'string') currentQuery = q.trim();
        if (!currentQuery) {
            toast(T.enterKeyword);
            return;
        }

        const nativeState = callNative('findInPage');
        if (nativeState) {
            setState(nativeState);
            setTimeout(function() {
                if (currentState.doneCounting && currentState.numberOfMatches === 0) {
                    toast(T.noMatch);
                }
            }, 500);
            return;
        }

        currentState = { supported: false, activeMatchOrdinal: -1, numberOfMatches: fallbackFind(true) ? 1 : 0, doneCounting: true, displayIndex: 0 };
        toast(currentState.numberOfMatches ? T.nativeUnavailable : T.noMatch);
        refreshPanel();
    }

    function go(forward, q) {
        if (!currentQuery) {
            doSearch(q);
            return;
        }
        const nativeState = callNative('findNextInPage', forward);
        if (nativeState) {
            setState(nativeState);
        } else {
            fallbackFind(forward);
        }
    }

    function clearSearch() {
        currentQuery = '';
        const nativeState = callNative('clearFindInPage');
        currentState = parseState(nativeState) || { supported: false, activeMatchOrdinal: -1, numberOfMatches: 0, doneCounting: true, displayIndex: 0 };
        refreshPanel();
    }

    function statusText() {
        if (!currentQuery) return T.tip;
        if (!currentState.doneCounting) return T.searching;
        if (!currentState.numberOfMatches) return T.noMatch;
        return T.matchCount
            .replace('{0}', currentState.displayIndex || Math.max(0, currentState.activeMatchOrdinal + 1))
            .replace('{1}', currentState.numberOfMatches);
    }

    function refreshPanel() {
        hcj.panel.send({ type: 'findstate', text: statusText(), has: currentState.numberOfMatches > 0, query: currentQuery, labels: { prev: T.prev, next: T.next, clear: T.clear, placeholder: T.placeholder } });
    }




    window.__wtaFindInPageNativeUpdate = setState;

    

    hcj.panel.onMessage(function (m) {
        if (!m) return;
        if (m.cmd === 'refresh') { refreshPanel(); return; }
        if (m.cmd === 'act') {
            if (m.action === 'findSearch') doSearch(m.arg);
            else if (m.action === 'findNext') go(true, m.arg);
            else if (m.action === 'findPrev') go(false, m.arg);
            else if (m.action === 'findClear') clearSearch();
        }
    });
    hcj.on('action', function () { hcj.panel.open(); });
})();
