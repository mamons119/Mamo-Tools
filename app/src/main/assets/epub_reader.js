(function () {
  if (window.mt) { return; }
  var S = {mode: 'scroll', tm: null, touched: false, wired: false};
  function SE() { return document.scrollingElement || document.documentElement; }
  function W() { return document.documentElement.clientWidth || window.innerWidth; }
  function H() { return window.innerHeight; }
  function docH() { return Math.max(SE().scrollHeight, document.body ? document.body.scrollHeight : 0); }
  function maxY() { return Math.max(0, docH() - H()); }
  function pages() { return Math.max(1, Math.round(SE().scrollWidth / W())); }
  function curPage() { return Math.round((window.pageXOffset || SE().scrollLeft || 0) / W()); }
  function frac() {
    if (S.mode === 'page') { return curPage() / pages(); }
    var h = docH();
    return h <= 0 ? 0 : (window.pageYOffset / h);
  }
  function atEnd() {
    if (S.mode === 'page') { return curPage() >= pages() - 1; }
    return window.pageYOffset >= maxY() - 3;
  }
  function padPages() {
    if (S.mode !== 'page' || !document.body) { return; }
    var keep = window.pageXOffset || 0;
    var old = document.getElementById('mt-pad');
    if (old && old.parentNode) { old.parentNode.removeChild(old); }
    var n = Math.max(1, Math.ceil((SE().scrollWidth - 6) / W()));
    var d = document.createElement('div');
    d.id = 'mt-pad';
    d.style.cssText = 'position:absolute;left:' + (n * W() - 1) + 'px;top:0;width:1px;height:1px;';
    document.body.appendChild(d);
    if (keep > 0) { window.scrollTo(Math.round(keep / W()) * W(), 0); }
  }
  function report() { try { Android.pos(frac(), atEnd()); } catch (e) {} }
  function sched() { clearTimeout(S.tm); S.tm = setTimeout(report, 120); }
  function findAnchor(a) {
    var el = null;
    try { el = document.getElementById(a); } catch (e) {}
    if (!el) { var n = document.getElementsByName(a); if (n && n.length) { el = n[0]; } }
    return el;
  }
  function addBtn(parent, label, fn) {
    var b = document.createElement('button');
    b.textContent = label;
    b.style.cssText = 'padding:12px 18px;border-radius:12px;border:1px solid currentColor;font:600 15px sans-serif;background:transparent;color:inherit;';
    b.onclick = fn;
    parent.appendChild(b);
  }
  window.mt = {
    init: function (mode, hasPrev, hasNext) {
      S.mode = mode;
      S.touched = false;
      padPages();
      if (!S.wired) {
        S.wired = true;
        window.addEventListener('scroll', sched, {passive: true});
        window.addEventListener('load', padPages);
        window.addEventListener('resize', padPages);
        window.addEventListener('touchstart', function () { S.touched = true; }, {passive: true});
        document.addEventListener('click', function (e) {
          var t = e.target;
          if (t && t.closest && t.closest('a,button,input,select,textarea,audio,video')) { return; }
          var sel = window.getSelection ? String(window.getSelection()) : '';
          if (sel && sel.length > 0) { return; }
          var x = e.clientX / W();
          try { Android.tap(x < 0.28 ? 0 : (x > 0.72 ? 2 : 1)); } catch (err) {}
        }, true);
      }
      if (mode === 'scroll' && !document.getElementById('mt-nav') && document.body) {
        var d = document.createElement('div');
        d.id = 'mt-nav';
        d.style.cssText = 'display:flex;gap:12px;justify-content:center;align-items:center;padding:28px 0 56px;clear:both;';
        if (hasPrev) { addBtn(d, '‹ Precedente', function () { Android.prev(); }); }
        if (hasNext) { addBtn(d, 'Successivo ›', function () { Android.next(); }); }
        else {
          var f = document.createElement('div');
          f.textContent = 'Fine del libro';
          f.style.cssText = 'font:14px sans-serif;opacity:.6;';
          d.appendChild(f);
        }
        document.body.appendChild(d);
      }
    },
    restore: function (f, anchor, end) {
      function apply() {
        if (anchor) {
          var el = findAnchor(anchor);
          if (el) {
            if (S.mode === 'page') {
              var r = el.getBoundingClientRect();
              var x = r.left + (window.pageXOffset || 0);
              window.scrollTo(Math.floor(x / W()) * W(), 0);
            } else { el.scrollIntoView(); }
            return;
          }
        }
        if (end) {
          if (S.mode === 'page') { window.scrollTo((pages() - 1) * W(), 0); } else { window.scrollTo(0, maxY()); }
          return;
        }
        if (f > 0) {
          if (S.mode === 'page') { window.scrollTo(Math.min(pages() - 1, Math.floor(f * pages() + 1e-4)) * W(), 0); }
          else { window.scrollTo(0, f * docH()); }
        } else { window.scrollTo(0, 0); }
      }
      apply();
      report();
      function again() { padPages(); if (!S.touched) { apply(); report(); } }
      if (document.readyState !== 'complete') { window.addEventListener('load', again); }
      if (document.fonts && document.fonts.ready) { document.fonts.ready.then(again); }
      setTimeout(again, 500);
    },
    go: function (dir) {
      S.touched = true;
      if (S.mode === 'page') {
        var p = curPage() + dir;
        if (p < 0 || p >= pages()) { return false; }
        window.scrollTo(p * W(), 0);
        sched();
        return true;
      }
      var y = window.pageYOffset;
      if (dir > 0 && y >= maxY() - 3) { return false; }
      if (dir < 0 && y <= 0) { return false; }
      var ny = Math.max(0, Math.min(maxY(), y + dir * H() * 0.88));
      try { window.scrollTo({top: ny, behavior: 'smooth'}); } catch (e) { window.scrollTo(0, ny); }
      sched();
      return true;
    },
    report: report
  };
})();
