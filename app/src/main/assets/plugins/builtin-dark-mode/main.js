(function () {
  'use strict';

  var rawLang = (hcj.lang || 'zh').toLowerCase();
  var LANG = rawLang.indexOf('ar') === 0 ? 'ar' : (rawLang.indexOf('zh') === 0 ? 'zh' : 'en');
  var T = ({
    zh: { on: '深色模式已开启', off: '深色模式已关闭', desc: '智能反色，保护眼睛', turnOn: '开启深色模式', turnOff: '关闭深色模式' },
    en: { on: 'Dark Mode On', off: 'Dark Mode Off', desc: 'Smart inversion, protect your eyes', turnOn: 'Turn On Dark Mode', turnOff: 'Turn Off Dark Mode' },
    ar: { on: 'الوضع الداكن مفعل', off: 'الوضع الداكن موقف', desc: 'عكس ذكي، حماية العين', turnOn: 'تفعيل الوضع الداكن', turnOff: 'إيقاف الوضع الداكن' }
  })[LANG] || {};

  var enabled = hcj.config.get('enabled', false);
  var styleEl = null;

  var darkCSS = 'html,html body{filter:invert(1) hue-rotate(180deg)!important;-webkit-filter:invert(1) hue-rotate(180deg)!important;background:#111!important}' +
    'img,video,picture,canvas,svg,iframe,[style*="background-image"],embed,object{filter:invert(1) hue-rotate(180deg)!important;-webkit-filter:invert(1) hue-rotate(180deg)!important}';

  function styleParent() {
    return document.head || document.documentElement;
  }

  function apply() {
    if (enabled) {
      if (!styleEl || !styleEl.parentNode) {
        styleEl = document.createElement('style');
        styleEl.id = 'wta-dark-mode';
        var parent = styleParent();
        if (parent) {
          parent.appendChild(styleEl);
        } else {
          var obs = new MutationObserver(function (m, o) {
            var p = styleParent();
            if (p) { p.appendChild(styleEl); styleEl.textContent = darkCSS; o.disconnect(); }
          });
          var target = document.documentElement || document.body;
          if (target instanceof Node) obs.observe(target, { childList: true, subtree: true });
          return;
        }
      }
      styleEl.textContent = darkCSS;
    } else if (styleEl) {
      styleEl.textContent = '';
    }
    hcj.badge(enabled ? 'ON' : '', '#6366f1');
  }

  function pushState() {
    hcj.panel.send({ type: 'state', enabled: enabled, t: T });
  }

  function toggle() {
    enabled = !enabled;
    hcj.config.set('enabled', enabled);
    apply();
    pushState();
  }

  hcj.on('action', function () { toggle(); });
  hcj.panel.onMessage(function (m) {
    if (!m) return;
    if (m.cmd === 'toggle') toggle();
    if (m.cmd === 'getState') pushState();
  });

  apply();
  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', function () { if (enabled) apply(); });
  }
})();
