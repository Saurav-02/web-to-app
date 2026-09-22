
(function() {
    'use strict';

    function toast(msg) { hcj.notify((hcj.manifest && hcj.manifest.name) || 'plugin', String(msg)); }
    function pushPanel() { hcj.panel.send({ type: 'panel', html: getPanelHtml() }); }
    function pushHtml(html) { hcj.panel.send({ type: 'panel', html: html }); }


    // 多语言支持
    const LANG = (hcj.lang || 'zh').toLowerCase().startsWith('ar') ? 'ar' :
                 (hcj.lang || 'zh').toLowerCase().startsWith('zh') ? 'zh' : 'en';
    const I18N = {
        zh: { name: '网页分析', pageInfo: '页面信息', title: '标题', domain: '域名', perf: '性能数据', loadTime: '加载时间(ms)', domReady: 'DOM就绪(ms)', stats: '元素统计', scripts: '脚本', styles: '样式', images: '图片', links: '链接', forms: '表单', iframes: '内嵌框架', videos: '视频' },
        en: { name: 'Web Analyzer', pageInfo: 'Page Info', title: 'Title', domain: 'Domain', perf: 'Performance', loadTime: 'Load Time(ms)', domReady: 'DOM Ready(ms)', stats: 'Element Stats', scripts: 'Scripts', styles: 'Styles', images: 'Images', links: 'Links', forms: 'Forms', iframes: 'Iframes', videos: 'Videos' },
        ar: { name: 'محلل الويب', pageInfo: 'معلومات الصفحة', title: 'العنوان', domain: 'النطاق', perf: 'الأداء', loadTime: 'وقت التحميل(ms)', domReady: 'DOM جاهز(ms)', stats: 'إحصائيات العناصر', scripts: 'السكريبتات', styles: 'الأنماط', images: 'الصور', links: 'الروابط', forms: 'النماذج', iframes: 'الإطارات', videos: 'الفيديوهات' }
    };
    const T = I18N[LANG] || I18N.en;

    function getPageInfo() {
        const scripts = document.querySelectorAll('script[src]').length;
        const styles = document.querySelectorAll('link[rel="stylesheet"]').length;
        const images = document.querySelectorAll('img').length;
        const links = document.querySelectorAll('a[href]').length;
        const forms = document.querySelectorAll('form').length;
        const iframes = document.querySelectorAll('iframe').length;
        const videos = document.querySelectorAll('video').length;

        return { scripts, styles, images, links, forms, iframes, videos };
    }

    function getPanelHtml() {
        const info = getPageInfo();
        const perf = performance.timing;
        const loadTime = perf.loadEventEnd - perf.navigationStart;
        const domReady = perf.domContentLoadedEventEnd - perf.navigationStart;

        return '<div class="wta-analyzer-panel">' +
            '<div class="wta-analyzer-section"><div class="wta-analyzer-section-title">' + T.pageInfo + '</div>' +
            '<div class="wta-analyzer-info-row"><span class="wta-analyzer-label">' + T.title + '</span><span class="wta-analyzer-value">' + (document.title || '-') + '</span></div>' +
            '<div class="wta-analyzer-info-row"><span class="wta-analyzer-label">' + T.domain + '</span><span class="wta-analyzer-value">' + location.hostname + '</span></div></div>' +
            '<div class="wta-analyzer-section"><div class="wta-analyzer-section-title">' + T.perf + '</div>' +
            '<div class="wta-analyzer-perf-grid">' +
            '<div class="wta-analyzer-perf-card"><div class="wta-analyzer-perf-num">' + (loadTime > 0 ? loadTime : '-') + '</div><div class="wta-analyzer-perf-label">' + T.loadTime + '</div></div>' +
            '<div class="wta-analyzer-perf-card" style="background:var(--wta-surface-dim,#eff6ff)"><div class="wta-analyzer-perf-num" style="color:var(--wta-accent,#3b82f6)">' + (domReady > 0 ? domReady : '-') + '</div><div class="wta-analyzer-perf-label">' + T.domReady + '</div></div></div></div>' +
            '<div class="wta-analyzer-section"><div class="wta-analyzer-section-title">' + T.stats + '</div>' +
            '<div class="wta-analyzer-stat-grid">' +
            [['<svg style="display:block;margin:0 auto" width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M14 2H6a2 2 0 00-2 2v16a2 2 0 002 2h12a2 2 0 002-2V8z"/><polyline points="14 2 14 8 20 8"/><path d="M8 13h8"/><path d="M8 17h5"/></svg>', info.scripts, T.scripts],
             ['<svg style="display:block;margin:0 auto" width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M12 2a10 10 0 000 20c1.1 0 2-.9 2-2 0-.5-.2-1-.5-1.4-.3-.4-.5-.8-.5-1.1 0-.8.7-1.5 1.5-1.5H16a6 6 0 006-6c0-4.4-4.5-8-10-8z"/><circle cx="7.5" cy="11" r="1"/><circle cx="10" cy="6.8" r="1"/><circle cx="14.5" cy="6.8" r="1"/><circle cx="17.5" cy="11" r="1"/></svg>', info.styles, T.styles],
             ['<svg style="display:block;margin:0 auto" width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><rect x="3" y="3" width="18" height="18" rx="2"/><circle cx="9" cy="9" r="2"/><path d="M21 15l-5-5L5 21"/></svg>', info.images, T.images],
             ['<svg style="display:block;margin:0 auto" width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M10 13a5 5 0 007.5.5l3-3a5 5 0 00-7-7l-1.8 1.8"/><path d="M14 11a5 5 0 00-7.5-.5l-3 3a5 5 0 007 7l1.8-1.8"/></svg>', info.links, T.links],
             ['<svg style="display:block;margin:0 auto" width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M11 4H4a2 2 0 00-2 2v14a2 2 0 002 2h14a2 2 0 002-2v-7"/><path d="M18.5 2.5a2.1 2.1 0 013 3L12 15l-4 1 1-4z"/></svg>', info.forms, T.forms],
             ['<svg style="display:block;margin:0 auto" width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><polygon points="12 2 2 7 12 12 22 7 12 2"/><polyline points="2 17 12 22 22 17"/><polyline points="2 12 12 17 22 12"/></svg>', info.iframes, T.iframes],
             ['<svg style="display:block;margin:0 auto" width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><rect x="2" y="4" width="13" height="16" rx="2"/><path d="M15 9l7-4v14l-7-4z"/></svg>', info.videos, T.videos]].map(function(s) {
                return '<div class="wta-analyzer-stat"><div class="wta-analyzer-stat-icon">' + s[0] + '</div><div class="wta-analyzer-stat-num">' + s[1] + '</div><div class="wta-analyzer-stat-label">' + s[2] + '</div></div>';
            }).join('') +
            '</div></div></div>';
    }

    
    var ACT = {};
    hcj.panel.onMessage(function (m) {
        if (!m) return;
        if (m.cmd === 'refresh') { pushPanel(); return; }
        if (m.cmd === 'act' && ACT[m.action]) { ACT[m.action](m.arg || ''); }
    });
    hcj.on('action', function () { hcj.panel.open(); });
})();
