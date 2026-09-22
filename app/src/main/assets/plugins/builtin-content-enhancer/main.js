
(function() {
    'use strict';

    function toast(msg) { hcj.notify((hcj.manifest && hcj.manifest.name) || 'plugin', String(msg)); }
    function pushPanel() { hcj.panel.send({ type: 'panel', html: getPanelHtml() }); }
    function pushHtml(html) { hcj.panel.send({ type: 'panel', html: html }); }


    // 多语言支持
    const LANG = (hcj.lang || 'zh').toLowerCase().startsWith('ar') ? 'ar' :
                 (hcj.lang || 'zh').toLowerCase().startsWith('zh') ? 'zh' : 'en';
    const I18N = {
        zh: { name: '内容增强', enableCopy: '解除复制限制', copyText: '复制页面文本', copyHtml: '复制页面HTML', toTop: '回到顶部', toBottom: '滚动到底部', copyEnabled: '已解除复制限制', textCopied: '页面文本已复制', htmlCopied: '页面HTML已复制', atTop: '已回到顶部', atBottom: '已到达底部' },
        en: { name: 'Content Enhance', enableCopy: 'Enable Copy', copyText: 'Copy Page Text', copyHtml: 'Copy Page HTML', toTop: 'To Top', toBottom: 'To Bottom', copyEnabled: 'Copy restriction removed', textCopied: 'Page text copied', htmlCopied: 'Page HTML copied', atTop: 'At top', atBottom: 'At bottom' },
        ar: { name: 'تحسين المحتوى', enableCopy: 'تفعيل النسخ', copyText: 'نسخ نص الصفحة', copyHtml: 'نسخ HTML', toTop: 'إلى الأعلى', toBottom: 'إلى الأسفل', copyEnabled: 'تم إزالة قيود النسخ', textCopied: 'تم نسخ النص', htmlCopied: 'تم نسخ HTML', atTop: 'في الأعلى', atBottom: 'في الأسفل' }
    };
    const T = I18N[LANG] || I18N.en;

    function enableCopy() {
        document.body.style.userSelect = 'auto';
        document.body.style.webkitUserSelect = 'auto';
        ['copy', 'cut', 'paste', 'selectstart', 'contextmenu'].forEach(e => {
            document.addEventListener(e, ev => ev.stopPropagation(), true);
        });
        const style = document.createElement('style');
        style.textContent = '*{user-select:auto!important;-webkit-user-select:auto!important}';
        document.head.appendChild(style);
        toast(T.copyEnabled);
    }

    function copyPageText() {
        const text = document.body.innerText;
        navigator.clipboard?.writeText(text).then(() => toast(T.textCopied));
    }

    function copyPageHtml() {
        const html = document.documentElement.outerHTML;
        navigator.clipboard?.writeText(html).then(() => toast(T.htmlCopied));
    }

    function scrollToTop() { window.scrollTo({ top: 0, behavior: 'smooth' }); toast(T.atTop); }
    function scrollToBottom() { window.scrollTo({ top: document.body.scrollHeight, behavior: 'smooth' }); toast(T.atBottom); }

    function updatePanelUI() {
        hcj.panel.send({ type: 'state', labels: { enableCopy: T.enableCopy, copyText: T.copyText, copyHtml: T.copyHtml, toTop: T.toTop, toBottom: T.toBottom } });
    }

    var ACT = {
        enableCopy: enableCopy,
        copyText: copyPageText,
        copyHtml: copyPageHtml,
        toTop: scrollToTop,
        toBottom: scrollToBottom
    };

    
    hcj.panel.onMessage(function (m) {
        if (!m) return;
        if (m.cmd === 'refresh') { updatePanelUI(); return; }
        if (m.cmd === 'act' && ACT[m.action]) ACT[m.action](m.arg || '');
    });
    hcj.on('action', function () { hcj.panel.open(); });
})();
