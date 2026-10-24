(function () {
  'use strict';
  var root = document.documentElement;
  var body = document.body;

  // ---- theme: auto (follow the system) -> dark -> light
  var THEME_KEY = 'master-data-api-theme';
  var themeButton = document.getElementById('theme');
  function currentTheme() { return root.getAttribute('data-theme') || ''; }
  function showTheme() {
    var t = currentTheme();
    themeButton.textContent = 'Theme: ' + (t || 'auto');
  }
  function setTheme(t) {
    if (t === 'light' || t === 'dark') { root.setAttribute('data-theme', t); } else { root.removeAttribute('data-theme'); }
    try { localStorage.setItem(THEME_KEY, t); } catch (e) { /* storage may be blocked */ }
    showTheme();
  }
  try {
    var saved = localStorage.getItem(THEME_KEY);
    if (saved === 'light' || saved === 'dark') { root.setAttribute('data-theme', saved); }
  } catch (e) { /* storage may be blocked */ }
  showTheme();
  themeButton.addEventListener('click', function () {
    var t = currentTheme();
    setTheme(t === '' ? 'dark' : t === 'dark' ? 'light' : '');
  });

  // ---- narrow screens: the rail opens as a panel
  var menu = document.getElementById('menu');
  menu.addEventListener('click', function () {
    var open = body.classList.toggle('nav-open');
    menu.setAttribute('aria-expanded', open ? 'true' : 'false');
  });
  document.querySelector('.rail').addEventListener('click', function (e) {
    if (e.target.closest('a')) { body.classList.remove('nav-open'); menu.setAttribute('aria-expanded', 'false'); }
  });

  // ---- search: every word must appear in the endpoint's path, summary, description, parameters or example titles
  var ops = Array.prototype.slice.call(document.querySelectorAll('.op'));
  var railLinks = {};
  Array.prototype.forEach.call(document.querySelectorAll('.rail [data-op]'), function (a) {
    railLinks[a.getAttribute('data-op')] = a;
  });
  var input = document.getElementById('q');
  var empty = document.getElementById('empty');
  var found = document.getElementById('found');
  var total = ops.length;

  function filter() {
    var words = input.value.toLowerCase().split(/\s+/).filter(Boolean);
    var shown = 0;
    ops.forEach(function (op) {
      var haystack = op.getAttribute('data-search');
      var ok = words.every(function (w) { return haystack.indexOf(w) !== -1; });
      op.hidden = !ok;
      if (railLinks[op.id]) { railLinks[op.id].hidden = !ok; }
      if (ok) { shown++; }
    });
    Array.prototype.forEach.call(document.querySelectorAll('.area-block'), function (block) {
      block.hidden = !block.querySelector('.op:not([hidden])');
    });
    Array.prototype.forEach.call(document.querySelectorAll('.rail .area'), function (area) {
      area.hidden = !area.querySelector('[data-op]:not([hidden])');
    });
    empty.hidden = shown !== 0;
    found.hidden = words.length === 0;
    found.textContent = shown + ' of ' + total + ' match';
  }
  input.addEventListener('input', filter);
  document.addEventListener('keydown', function (e) {
    var tag = (e.target && e.target.tagName) || '';
    if (e.key === '/' && tag !== 'INPUT' && tag !== 'TEXTAREA') { e.preventDefault(); input.focus(); }
    if (e.key === 'Escape' && document.activeElement === input) { input.value = ''; filter(); input.blur(); }
  });
  filter();

  // ---- copy buttons
  document.addEventListener('click', function (e) {
    var button = e.target.closest('.copy');
    if (!button) { return; }
    var pre = button.parentNode.querySelector('pre');
    function flash(text) {
      button.textContent = text;
      setTimeout(function () { button.textContent = 'Copy'; }, 1400);
    }
    function select() {
      var range = document.createRange();
      range.selectNodeContents(pre);
      var selection = window.getSelection();
      selection.removeAllRanges();
      selection.addRange(range);
      flash('Selected');
    }
    if (navigator.clipboard && navigator.clipboard.writeText) {
      navigator.clipboard.writeText(pre.textContent).then(function () { flash('Copied'); }, select);
    } else {
      select();
    }
  });

  // ---- highlight the endpoint being read in the rail
  if ('IntersectionObserver' in window) {
    var current = null;
    var observer = new IntersectionObserver(function (entries) {
      entries.forEach(function (entry) {
        if (!entry.isIntersecting) { return; }
        var link = railLinks[entry.target.id];
        if (!link) { return; }
        if (current) { current.classList.remove('current'); }
        link.classList.add('current');
        current = link;
      });
    }, { rootMargin: '-15% 0px -75% 0px' });
    ops.forEach(function (op) { observer.observe(op); });
  }
})();
