(function () {
  'use strict';

  function qs(sel, root) {
    return (root || document).querySelector(sel);
  }

  function qsa(sel, root) {
    return Array.prototype.slice.call((root || document).querySelectorAll(sel));
  }

  function initHeaderScroll() {
    var header = qs('.header');
    if (!header) return;
    var ticking = false;
    function update() {
      if (window.scrollY > 40) header.classList.add('scrolled');
      else header.classList.remove('scrolled');
      ticking = false;
    }
    window.addEventListener('scroll', function () {
      if (!ticking) {
        requestAnimationFrame(update);
        ticking = true;
      }
    }, { passive: true });
    update();
  }

  function initMobileNav() {
    var toggle = qs('.mobile-menu-toggle');
    var menu = qs('#mobileMenu') || qs('.mobile-menu');
    if (!toggle || !menu) return;

    function close() {
      menu.classList.remove('active');
      toggle.classList.remove('active');
      toggle.setAttribute('aria-expanded', 'false');
    }

    function open() {
      menu.classList.add('active');
      toggle.classList.add('active');
      toggle.setAttribute('aria-expanded', 'true');
    }

    toggle.addEventListener('click', function (e) {
      e.stopPropagation();
      if (menu.classList.contains('active')) close();
      else open();
    });

    document.addEventListener('click', function (e) {
      var header = qs('.header');
      if (menu.classList.contains('active') && header && !header.contains(e.target)) {
        close();
      }
    });

    qsa('a', menu).forEach(function (link) {
      link.addEventListener('click', close);
    });

    // Expose for legacy onclick="toggleMobileMenu()"
    window.toggleMobileMenu = function () {
      if (menu.classList.contains('active')) close();
      else open();
    };
  }

  function initSidebar() {
    var sidebar = qs('.sidebar');
    var overlay = qs('.sidebar-overlay');
    var openBtn = qs('[data-sidebar-open]');
    var closeBtn = qs('.sidebar-close');

    function open() {
      if (sidebar) sidebar.classList.add('open');
      if (overlay) overlay.classList.add('open');
    }
    function close() {
      if (sidebar) sidebar.classList.remove('open');
      if (overlay) overlay.classList.remove('open');
    }

    if (openBtn) openBtn.addEventListener('click', open);
    if (closeBtn) closeBtn.addEventListener('click', close);
    if (overlay) overlay.addEventListener('click', close);

    window.toggleSidebar = function () {
      if (sidebar && sidebar.classList.contains('open')) close();
      else open();
    };
  }

  function initReveal() {
    var nodes = qsa('.reveal');
    if (!nodes.length) return;
    if (!('IntersectionObserver' in window)) {
      nodes.forEach(function (n) { n.classList.add('visible'); });
      return;
    }
    var io = new IntersectionObserver(function (entries) {
      entries.forEach(function (entry) {
        if (entry.isIntersecting) {
          entry.target.classList.add('visible');
          io.unobserve(entry.target);
        }
      });
    }, { threshold: 0.12, rootMargin: '0px 0px -40px 0px' });
    nodes.forEach(function (n) { io.observe(n); });
  }

  function initSmoothAnchors() {
    qsa('a[href^="#"]').forEach(function (anchor) {
      anchor.addEventListener('click', function (e) {
        var id = anchor.getAttribute('href');
        if (!id || id === '#') return;
        var target = qs(id);
        if (!target) return;
        e.preventDefault();
        target.scrollIntoView({ behavior: 'smooth', block: 'start' });
      });
    });
  }

  function initBlogFilters() {
    var buttons = qsa('.filter-btn');
    if (!buttons.length) return;

    buttons.forEach(function (btn) {
      btn.addEventListener('click', function () {
        var filter = btn.getAttribute('data-filter')
          || btn.getAttribute('data-cat')
          || 'all';
        buttons.forEach(function (b) { b.classList.remove('active'); });
        btn.classList.add('active');
        qsa('.blog-card').forEach(function (card) {
          var catAttr = card.getAttribute('data-category');
          var catEl = qs('.blog-card-category', card);
          var cat = catAttr || (catEl ? catEl.textContent.trim() : '');
          var show = filter === 'all' || cat === filter
            || (catAttr && catAttr.split(/\s+/).indexOf(filter) !== -1);
          card.style.display = show ? '' : 'none';
        });
      });
    });
  }

  document.addEventListener('DOMContentLoaded', function () {
    initHeaderScroll();
    initMobileNav();
    initSidebar();
    initReveal();
    initSmoothAnchors();
    initBlogFilters();
  });
})();
