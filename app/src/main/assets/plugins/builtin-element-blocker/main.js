
(function() {
    'use strict';

    var ACT = {};
    function toast(msg) { hcj.notify((hcj.manifest && hcj.manifest.name) || 'plugin', String(msg)); }
    function pushPanel() { hcj.panel.send({ type: 'panel', html: getPanelHtml() }); }
    function pushHtml(html) { hcj.panel.send({ type: 'panel', html: html }); }


    // 多语言支持
    const LANG = (hcj.lang || 'zh').toLowerCase().startsWith('ar') ? 'ar' :
                 (hcj.lang || 'zh').toLowerCase().startsWith('zh') ? 'zh' : 'en';
    const I18N = {
        zh: {
            name: '元素屏蔽',
            blocked: '已屏蔽元素',
            unblocked: '已取消屏蔽',
            selected: '已选中',
            dblClickToBlock: '双击屏蔽',
            selectMode: '选择模式：单击选择，双击屏蔽，按 ESC 退出',
            clearedAll: '已清除所有屏蔽',
            selectElement: '选择要屏蔽的元素',
            blockedCount: '已屏蔽 {0} 个元素',
            delete: '删除',
            copyRule: '复制规则',
            ruleCopied: '屏蔽规则已复制，可粘贴到去广告自定义规则',
            clearAll: '清除所有屏蔽',
            clickToSelect: '点击上方按钮选择要屏蔽的元素'
        },
        en: {
            name: 'Element Blocker',
            blocked: 'Element blocked',
            unblocked: 'Unblocked',
            selected: 'Selected',
            dblClickToBlock: 'double-click to block',
            selectMode: 'Select mode: click to select, double-click to block, ESC to exit',
            clearedAll: 'All blocks cleared',
            selectElement: 'Select element to block',
            blockedCount: '{0} elements blocked',
            delete: 'Delete',
            copyRule: 'Copy rule',
            ruleCopied: 'Block rule copied; paste it into ad-block custom rules',
            clearAll: 'Clear all blocks',
            clickToSelect: 'Click the button above to select elements'
        },
        ar: {
            name: 'مانع العناصر',
            blocked: 'تم حظر العنصر',
            unblocked: 'تم إلغاء الحظر',
            selected: 'محدد',
            dblClickToBlock: 'انقر مرتين للحظر',
            selectMode: 'وضع التحديد: انقر للتحديد، انقر مرتين للحظر، ESC للخروج',
            clearedAll: 'تم مسح جميع الحظر',
            selectElement: 'حدد العنصر للحظر',
            blockedCount: 'تم حظر {0} عنصر',
            delete: 'حذف',
            copyRule: 'نسخ القاعدة',
            ruleCopied: 'تم نسخ قاعدة الحظر؛ الصقها في القواعد المخصصة لمنع الإعلانات',
            clearAll: 'مسح جميع الحظر',
            clickToSelect: 'انقر على الزر أعلاه لتحديد العناصر'
        }
    };
    const T = I18N[LANG] || I18N.en;
    const STORAGE_KEY = 'wta_blocked_elements';
    let blockedSelectors = JSON.parse(localStorage.getItem(STORAGE_KEY) || '[]');
    let selectMode = false;
    let hoveredElement = null;
    let highlightOverlay = null;

    // Create高亮覆盖层
    function createOverlay() {
        if (highlightOverlay) return;
        highlightOverlay = document.createElement('div');
        highlightOverlay.id = 'wta-element-highlight';
        highlightOverlay.style.cssText = 'position:fixed;pointer-events:none;z-index:2147483646;border:2px solid #ef4444;background:rgba(239,68,68,0.15);transition:all 0.1s ease;display:none';
        document.body.appendChild(highlightOverlay);
    }

    // Generate元素的唯一选择器
    function getSelector(el) {
        if (!el || el === document.body || el === document.documentElement) return null;
        if (el.id) return '#' + CSS.escape(el.id);

        let path = [];
        while (el && el !== document.body && el !== document.documentElement) {
            let selector = el.tagName.toLowerCase();
            if (el.className && typeof el.className === 'string') {
                const classes = el.className.trim().split(/\s+/).filter(c => c && !c.includes('wta-'));
                if (classes.length) selector += '.' + classes.map(c => CSS.escape(c)).join('.');
            }
            const parent = el.parentElement;
            if (parent) {
                const siblings = Array.from(parent.children).filter(c => c.tagName === el.tagName);
                if (siblings.length > 1) {
                    const idx = siblings.indexOf(el) + 1;
                    selector += ':nth-of-type(' + idx + ')';
                }
            }
            path.unshift(selector);
            el = parent;
            if (path.length >= 4) break;
        }
        return path.join(' > ');
    }

    // App屏蔽规则
    function applyBlockedRules() {
        let styleEl = document.getElementById('wta-blocked-styles');
        if (!styleEl) {
            styleEl = document.createElement('style');
            styleEl.id = 'wta-blocked-styles';
            document.head.appendChild(styleEl);
        }
        if (blockedSelectors.length) {
            styleEl.textContent = blockedSelectors.map(s => s + '{display:none!important}').join('');
        } else {
            styleEl.textContent = '';
        }
    }

    // Save屏蔽规则
    function saveRules() {
        localStorage.setItem(STORAGE_KEY, JSON.stringify(blockedSelectors));
        applyBlockedRules();
    }

    // 屏蔽元素
    function blockElement(selector) {
        if (!selector || blockedSelectors.includes(selector)) return;
        blockedSelectors.push(selector);
        saveRules();
        toast(T.blocked);
    }

    // Copy屏蔽规则到剪贴板 (issue #765)：拼成“域名##选择器”的去广告
    // 自定义规则格式，粘贴即用。navigator.clipboard 在非安全上下文不可用
    // （如 http 页面），此时回退到 textarea + execCommand。
    function copySelector(index) {
        const selector = blockedSelectors[index];
        if (!selector) return;
        let rule = selector;
        try {
            const host = window.location.hostname;
            if (host) rule = host + '##' + selector;
        } catch (e) { /* file/blob 页面无 hostname，用裸选择器 */ }
        function done(ok) {
            toast(ok ? T.ruleCopied : selector);
        }
        try {
            if (navigator.clipboard && navigator.clipboard.writeText) {
                navigator.clipboard.writeText(rule).then(function() { done(true); }, function() { legacyCopy(rule, done); });
            } else {
                legacyCopy(rule, done);
            }
        } catch (e) {
            legacyCopy(rule, done);
        }
    }

    function legacyCopy(text, done) {
        try {
            const ta = document.createElement('textarea');
            ta.value = text;
            ta.style.cssText = 'position:fixed;opacity:0;pointer-events:none';
            document.body.appendChild(ta);
            ta.select();
            const ok = document.execCommand('copy');
            document.body.removeChild(ta);
            done(!!ok);
        } catch (e) {
            done(false);
        }
    }

    // Cancel屏蔽
    function unblockElement(index) {
        blockedSelectors.splice(index, 1);
        saveRules();
        toast(T.unblocked);
        pushPanel();
    }

    // 鼠标移动事件
    function onMouseMove(e) {
        if (!selectMode) return;
        const el = document.elementFromPoint(e.clientX, e.clientY);
        if (!el || el === highlightOverlay || el.closest('#wta-module-panel') || el.closest('#wta-module-fab')) {
            if (highlightOverlay) highlightOverlay.style.display = 'none';
            hoveredElement = null;
            return;
        }
        hoveredElement = el;
        const rect = el.getBoundingClientRect();
        if (highlightOverlay) {
            highlightOverlay.style.display = 'block';
            highlightOverlay.style.left = rect.left + 'px';
            highlightOverlay.style.top = rect.top + 'px';
            highlightOverlay.style.width = rect.width + 'px';
            highlightOverlay.style.height = rect.height + 'px';
        }
    }

    // 单击选择
    function onClick(e) {
        if (!selectMode || !hoveredElement) return;
        e.preventDefault();
        e.stopPropagation();
        const selector = getSelector(hoveredElement);
        if (selector) {
            toast(T.selected + ': ' + hoveredElement.tagName.toLowerCase() + ' (' + T.dblClickToBlock + ')');
        }
    }

    // 双击屏蔽
    function onDblClick(e) {
        if (!selectMode || !hoveredElement) return;
        e.preventDefault();
        e.stopPropagation();
        const selector = getSelector(hoveredElement);
        if (selector) {
            blockElement(selector);
            exitSelectMode();
        }
    }

    // 进入选择模式
    function enterSelectMode() {
        selectMode = true;
        createOverlay();
        document.addEventListener('mousemove', onMouseMove, true);
        document.addEventListener('click', onClick, true);
        document.addEventListener('dblclick', onDblClick, true);
        document.body.style.cursor = 'crosshair';
        toast(T.selectMode);
        hcj.panel.close();

        // ESC 退出
        document.addEventListener('keydown', function escHandler(e) {
            if (e.key === 'Escape') {
                exitSelectMode();
                document.removeEventListener('keydown', escHandler);
            }
        });
    }

    // 退出选择模式
    function exitSelectMode() {
        selectMode = false;
        hoveredElement = null;
        if (highlightOverlay) highlightOverlay.style.display = 'none';
        document.removeEventListener('mousemove', onMouseMove, true);
        document.removeEventListener('click', onClick, true);
        document.removeEventListener('dblclick', onDblClick, true);
        document.body.style.cursor = '';
    }

    // 清除所有屏蔽
    function clearAll() {
        blockedSelectors = [];
        saveRules();
        toast(T.clearedAll);
        pushPanel();
    }

    function getPanelHtml() {
        let html = '<div class="wta-blocker-panel">' +
            '<button class="wta-blocker-select-btn" data-wta-action="enterSelectMode"><span><svg style="display:block" width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M6 3l14 9-6.2 1.3L17 20l-2.6 1.2-3.2-6.6L6 19V3z"/></svg></span> ' + T.selectElement + '</button>';

        html += '<div class="wta-blocker-count">' + T.blockedCount.replace('{0}', blockedSelectors.length) + '</div>';

        if (blockedSelectors.length) {
            html += '<div class="wta-blocker-list">';
            blockedSelectors.forEach(function(selector, i) {
                html += '<div class="wta-blocker-item">' +
                    '<span class="wta-blocker-item-selector">' + selector.replace(/</g, '&lt;') + '</span>' +
                    '<button class="wta-blocker-item-btn" data-wta-action="copySelector" data-wta-arg="' + i + '" title="' + T.copyRule + '">⧉</button>' +
                    '<button class="wta-blocker-item-btn" data-wta-action="unblock" data-wta-arg="' + i + '"><svg style="display:block" width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M6 6l12 12"/><path d="M18 6L6 18"/></svg></button></div>';
            });
            html += '</div>';
            html += '<button class="wta-blocker-clear-btn" data-wta-action="clearAllBlocks">' + T.clearAll + '</button>';
        } else {
            html += '<div class="wta-blocker-empty"><div class="wta-blocker-empty-icon"><svg style="display:block;margin:0 auto" width="32" height="32" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><circle cx="12" cy="12" r="9"/><circle cx="12" cy="12" r="5"/><circle cx="12" cy="12" r="1.5"/></svg></div><div>' + T.clickToSelect + '</div></div>';
        }

        return html + '</div>';
    }

    ACT.enterSelectMode = enterSelectMode;
    ACT.unblock = function(i) { unblockElement(parseInt(i)); };
    ACT.copySelector = function(i) { copySelector(parseInt(i)); };
    ACT.clearAllBlocks = clearAll;

    

    hcj.panel.onMessage(function (m) {
        if (!m) return;
        if (m.cmd === 'refresh') { pushPanel(); return; }
        if (m.cmd === 'act' && ACT[m.action]) { ACT[m.action](m.arg || ''); }
    });
    hcj.on('action', function () { hcj.panel.open(); });

    applyBlockedRules();
})();
