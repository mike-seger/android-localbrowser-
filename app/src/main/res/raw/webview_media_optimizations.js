(function() {
  if (window.__localweb_media_debug_installed__) return;
  window.__localweb_media_debug_installed__ = true;

  function safeToString(v) {
    try { return String(v); } catch (e) { return '[unstringifiable]'; }
  }

  window.addEventListener('unhandledrejection', function(ev) {
    var r = ev && ev.reason;
    console.log('[media-debug] unhandledrejection:', (r && r.name) || '', (r && r.message) || safeToString(r));
  });

  window.addEventListener('error', function(ev) {
    try {
      var msg = ev && (ev.message || (ev.error && ev.error.message) || ev.error);
      console.log('[media-debug] window.error:', safeToString(msg));
    } catch (e) {}
  });

  var origPlay = HTMLMediaElement.prototype.play;
  HTMLMediaElement.prototype.play = function() {
    try {
      try {
        console.log(
          '[media-debug] play() called:',
          (this && (this.currentSrc || this.src)) || '',
          'paused=' + (!!this.paused),
          'readyState=' + (this.readyState || 0),
          'networkState=' + (this.networkState || 0)
        );
      } catch (e) {}
      var p = origPlay.apply(this, arguments);
      if (p && typeof p.then === 'function') {
        p.then(function() { console.log('[media-debug] play() resolved'); });
        p.catch(function(err) {
          console.log('[media-debug] play() rejected:', (err && err.name) || '', (err && err.message) || safeToString(err));
        });
      }
      return p;
    } catch (err) {
      console.log('[media-debug] play() threw:', (err && err.name) || '', (err && err.message) || safeToString(err));
      throw err;
    }
  };

  // ---- Viewport + layout stabilization (WebView vs Chrome) ----
  function getViewportHeightPx() {
    try {
      var vv = window.visualViewport;
      var h = vv && typeof vv.height === 'number' ? vv.height : 0;
      if (h && h > 0) return h;
    } catch (e) {}

    try {
      var ih = (typeof window.innerHeight === 'number') ? window.innerHeight : 0;
      if (ih && ih > 0) return ih;
    } catch (e) {}

    try {
      var ch = document && document.documentElement ? document.documentElement.clientHeight : 0;
      if (ch && ch > 0) return ch;
    } catch (e) {}

    return 0;
  }

  function setAppVh(reason) {
    try {
      var h = getViewportHeightPx();
      // Ignore transient zeros/silly small values.
      if (!h || h < 100) return;
      var oneVhPx = (h / 100);
      document.documentElement.style.setProperty('--app-vh', oneVhPx + 'px');
      if (!window.__localweb_last_vh__ || Math.abs(window.__localweb_last_vh__ - h) > 1) {
        window.__localweb_last_vh__ = h;
        console.log('[media-debug] set --app-vh:', 'h=' + Math.round(h), '1vh=' + oneVhPx.toFixed(2), 'reason=' + (reason || ''));
      }
    } catch (e) {}
  }

  var __vhTimer = null;
  function scheduleVh(reason) {
    try {
      if (__vhTimer) clearTimeout(__vhTimer);
      __vhTimer = setTimeout(function() { setAppVh(reason); }, 0);
    } catch (e) {}
  }

  scheduleVh('install');
  window.addEventListener('resize', function() { scheduleVh('window.resize'); }, true);
  window.addEventListener('orientationchange', function() { scheduleVh('orientationchange'); }, true);
  try {
    if (window.visualViewport) {
      window.visualViewport.addEventListener('resize', function() { scheduleVh('visualViewport.resize'); }, true);
      window.visualViewport.addEventListener('scroll', function() { scheduleVh('visualViewport.scroll'); }, true);
    }
  } catch (e) {}

  // ---- Video visibility recovery ----
  function ensureBaseHeights() {
    try {
      var de = document.documentElement;
      var body = document.body;
      if (de) {
        de.style.height = '100%';
        de.style.minHeight = '100%';
      }
      if (body) {
        body.style.height = '100%';
        body.style.minHeight = '100%';
      }
    } catch (e) {}
  }

  function ensureFixStyleInstalled() {
    try {
      if (document.getElementById('__localweb_video_fix_style')) return;
      var style = document.createElement('style');
      style.id = '__localweb_video_fix_style';
      style.textContent = [
        'html, body { height: 100% !important; min-height: 100% !important; }',
        'video { max-width: 100% !important; }',
        // WebView sometimes shows a large default overlay play button between source switches.
        // Try to hide it so the page's own loading UX (spinner, skeleton, etc.) is visible instead.
        'video::-webkit-media-controls-overlay-play-button { display: none !important; -webkit-appearance: none !important; }',
        'video::-webkit-media-controls-start-playback-button { display: none !important; -webkit-appearance: none !important; }',

        // During transitions, WebView can also paint a full native controls overlay.
        // Only hide these while we believe the video is "loading/switching".
        'video.__localweb_loading::-webkit-media-controls { display: none !important; }',
        'video.__localweb_loading::-webkit-media-controls-enclosure { display: none !important; }',
        'video.__localweb_loading::-webkit-media-controls-panel { display: none !important; }',
        'video.__localweb_loading::-webkit-media-controls-overlay-enclosure { display: none !important; }',
        'video.__localweb_loading::-webkit-media-controls-play-button { display: none !important; }',
        'video.__localweb_loading::-webkit-media-controls-timeline { display: none !important; }',
        'video.__localweb_loading::-webkit-media-controls-current-time-display { display: none !important; }',
        'video.__localweb_loading::-webkit-media-controls-time-remaining-display { display: none !important; }',
        'video.__localweb_loading::-webkit-media-controls-mute-button { display: none !important; }',
        'video.__localweb_loading::-webkit-media-controls-volume-slider { display: none !important; }',
        'video.__localweb_loading::-webkit-media-controls-fullscreen-button { display: none !important; }'
      ].join('\n');
      (document.head || document.documentElement).appendChild(style);
    } catch (e) {}
  }

  function setVideoHiddenForSwitch(videoEl, on) {
    try {
      if (!videoEl || !videoEl.style) return;
      if (on) {
        if (!videoEl.__localweb_prev_opacity__) {
          // Track previous inline styles so we can restore precisely.
          videoEl.__localweb_prev_opacity__ = videoEl.style.opacity;
          videoEl.__localweb_prev_visibility__ = videoEl.style.visibility;
        }
        // Keep layout size but prevent the native play glyph from being visible.
        videoEl.style.opacity = '0';
        videoEl.style.visibility = 'hidden';
      } else {
        if (videoEl.__localweb_prev_opacity__ !== undefined) {
          videoEl.style.opacity = videoEl.__localweb_prev_opacity__;
          videoEl.__localweb_prev_opacity__ = undefined;
        } else {
          videoEl.style.opacity = '';
        }
        if (videoEl.__localweb_prev_visibility__ !== undefined) {
          videoEl.style.visibility = videoEl.__localweb_prev_visibility__;
          videoEl.__localweb_prev_visibility__ = undefined;
        } else {
          videoEl.style.visibility = '';
        }
      }
    } catch (e) {}
  }

  function setVideoAudioSuppressed(videoEl, on) {
    try {
      if (!videoEl) return;

      // User request: do not change audio/volume during transitions.
      // We still keep the visual transition (overlay/hide/collapse) logic.
      try {
        if (videoEl.__localweb_fade_timer__) {
          clearInterval(videoEl.__localweb_fade_timer__);
          videoEl.__localweb_fade_timer__ = null;
        }
      } catch (e) {}

      try {
        if (videoEl.__localweb_restore_audio_timer__) {
          clearTimeout(videoEl.__localweb_restore_audio_timer__);
          videoEl.__localweb_restore_audio_timer__ = null;
        }
      } catch (e) {}

      videoEl.__localweb_prev_muted__ = undefined;
      videoEl.__localweb_prev_volume__ = undefined;
      videoEl.__localweb_audio_suppressed__ = false;
    } catch (e) {}
  }

  function scheduleAudioRestore(videoEl, reason) {
    try {
      // Intentionally disabled: we no longer suppress/restore audio during transitions.
      if (!videoEl) return;
    } catch (e) {}
  }

  function beginVideoTransition(videoEl, reason) {
    try {
      if (!videoEl || !videoEl.classList) return;
      if (videoEl.classList.contains('__localweb_loading')) return;
      ensureFixStyleInstalled();
      setVideoLoading(videoEl, true, reason || 'transition');
      // Hide immediately so native glyph can't flash.
      setVideoHiddenForSwitch(videoEl, true);

      // Failsafe: if we never observe a clean "playing"/timeupdate (WebView quirks),
      // still end the transition after a short window.
      try {
        if (videoEl.__localweb_transition_failsafe_timer__) {
          clearTimeout(videoEl.__localweb_transition_failsafe_timer__);
          videoEl.__localweb_transition_failsafe_timer__ = null;
        }
      } catch (e) {}
      try {
        videoEl.__localweb_transition_failsafe_timer__ = setTimeout(function() {
          try {
            videoEl.__localweb_transition_failsafe_timer__ = null;
            // Only force-end if we still think we're loading.
            if (videoEl.classList && videoEl.classList.contains('__localweb_loading')) {
              endVideoTransition(videoEl, 'failsafe');
            }
          } catch (e) {}
        }, 1600);
      } catch (e) {}
    } catch (e) {}
  }

  function endVideoTransition(videoEl, reason) {
    try {
      if (!videoEl || !videoEl.classList) return;
      if (!videoEl.classList.contains('__localweb_loading')) return;

      // Don't end too early (this is where the native play glyph tends to flash).
      // Require playback to have actually started OR time to be advancing.
      var ok = false;
      try {
        ok = (!!videoEl && !videoEl.paused && !videoEl.ended);
      } catch (e) {}
      if (!ok) {
        try {
          var t = (typeof videoEl.currentTime === 'number') ? videoEl.currentTime : 0;
          var lastT = (typeof videoEl.__localweb_last_time__ === 'number') ? videoEl.__localweb_last_time__ : null;
          if (lastT !== null && isFinite(lastT) && isFinite(t) && (t - lastT) > 0.02) ok = true;
        } catch (e) {}
      }

      // If we can't prove it's running, don't end yet.
      if (!ok && reason !== 'failsafe') return;

      try {
        if (videoEl.classList.contains('__localweb_loading')) {
          videoEl.classList.remove('__localweb_loading');
          console.log('[media-debug] ui:video-loading=0 reason=' + (reason || ''));
        }
      } catch (e) {}

      // No extra delay: once playback is confirmed running/advancing, unhide immediately.
      // Use requestAnimationFrame to keep the overlay covering at least one paint.
      try {
        if (videoEl.__localweb_hide_overlay_timer__) {
          clearTimeout(videoEl.__localweb_hide_overlay_timer__);
          videoEl.__localweb_hide_overlay_timer__ = null;
        }
      } catch (e) {}
      try {
        requestAnimationFrame(function() {
          try {
            hideLoadingOverlay();
            setVideoHiddenForSwitch(videoEl, false);
          } catch (e) {}
        });
      } catch (e) {
        try { hideLoadingOverlay(); } catch (_e) {}
        try { setVideoHiddenForSwitch(videoEl, false); } catch (_e) {}
      }

      try { console.log('[media-debug] end transition:', reason || ''); } catch (e) {}
    } catch (e) {}
  }

  // ---- Custom loading overlay (covers WebView's native play glyph) ----
  function ensureLoadingOverlayInstalled() {
    try {
      if (document.getElementById('__localweb_video_loading_style')) return;
      var style = document.createElement('style');
      style.id = '__localweb_video_loading_style';
      style.textContent = `
      .__localweb_video_loading_overlay {
        position: fixed;
        left: 0;
        top: 0;
        width: 100%;
        height: 100%;
        display: none;
        z-index: 2147483647;
        background: transparent;
        pointer-events: none;
      }

      .__localweb_video_loading_overlay.__show {
        display: block;
      }

      .__localweb_spinner {
        width: 44px;
        height: 44px;
        /* Position 30px from top and right edges */
        position: absolute;
        top: 30px;
        right: 30px;
        border-radius: 50%;
        border: 4px solid rgba(255, 255, 255, 0.28);
        border-top-color: rgba(255, 255, 255, 0.85);
        animation: __localweb_spin 0.9s linear infinite;
      }

      @keyframes __localweb_spin {
        from {
          transform: rotate(0deg);
        }
        to {
          transform: rotate(360deg);
        }
      }
      `;
      (document.head || document.documentElement).appendChild(style);

      var overlay = document.createElement('div');
      overlay.className = '__localweb_video_loading_overlay';
      overlay.id = '__localweb_video_loading_overlay';
      var spinner = document.createElement('div');
      spinner.className = '__localweb_spinner';
      overlay.appendChild(spinner);
      (document.body || document.documentElement).appendChild(overlay);
    } catch (e) {}
  }

  function getLoadingOverlayEl() {
    try { return document.getElementById('__localweb_video_loading_overlay'); } catch (e) {}
    return null;
  }

  function positionOverlayToVideo(videoEl) {
    try {
      var overlay = getLoadingOverlayEl();
      if (!overlay || !videoEl || !videoEl.getBoundingClientRect) return;

      function applyRect(r) {
        try {
          var w = Math.max(0, Math.round(r.width || 0));
          var h = Math.max(0, Math.round(r.height || 0));
          if (w < 2 || h < 2) return false;
          overlay.style.left = Math.round(r.left || 0) + 'px';
          overlay.style.top = Math.round(r.top || 0) + 'px';
          overlay.style.width = w + 'px';
          overlay.style.height = h + 'px';
          overlay.__localweb_last_good_rect__ = { left: r.left || 0, top: r.top || 0, width: w, height: h };
          return true;
        } catch (e) {}
        return false;
      }

      // 1) Prefer the actual video element rect.
      var r = videoEl.getBoundingClientRect();
      if (r && applyRect(r)) return;

      // 2) Fallback to the player container (covers where the video should be).
      try {
        var pc = document.getElementById('player-container') || document.getElementById('player');
        if (pc && pc.getBoundingClientRect) {
          var pr = pc.getBoundingClientRect();
          if (pr && applyRect(pr)) return;
        }
      } catch (e) {}

      // 3) If we had a last-known good rect, keep using it (avoid a brief 0x0 flicker).
      try {
        var last = overlay.__localweb_last_good_rect__;
        if (last && last.width > 1 && last.height > 1) {
          overlay.style.left = Math.round(last.left) + 'px';
          overlay.style.top = Math.round(last.top) + 'px';
          overlay.style.width = Math.round(last.width) + 'px';
          overlay.style.height = Math.round(last.height) + 'px';
          return;
        }
      } catch (e) {}

      // 4) Last resort: cover the viewport.
      try {
        var ww = (typeof window.innerWidth === 'number') ? window.innerWidth : 0;
        var hh = (typeof window.innerHeight === 'number') ? window.innerHeight : 0;
        if (ww > 1 && hh > 1) {
          overlay.style.left = '0px';
          overlay.style.top = '0px';
          overlay.style.width = Math.round(ww) + 'px';
          overlay.style.height = Math.round(hh) + 'px';
        }
      } catch (e) {}
    } catch (e) {}
  }

  function showLoadingOverlay(videoEl) {
    try {
      ensureLoadingOverlayInstalled();
      var overlay = getLoadingOverlayEl();
      if (!overlay) return;
      overlay.__localweb_target__ = videoEl || null;
      overlay.classList.add('__show');

      // Keep it tracking while loading.
      if (!overlay.__localweb_raf__) {
        var tick = function() {
          try {
            if (!overlay.classList.contains('__show')) {
              overlay.__localweb_raf__ = null;
              return;
            }
            positionOverlayToVideo(overlay.__localweb_target__);
          } catch (e) {}
          overlay.__localweb_raf__ = requestAnimationFrame(tick);
        };
        overlay.__localweb_raf__ = requestAnimationFrame(tick);
      }
      // Initial position.
      positionOverlayToVideo(videoEl);
    } catch (e) {}
  }

  function hideLoadingOverlay() {
    try {
      var overlay = getLoadingOverlayEl();
      if (!overlay) return;
      overlay.classList.remove('__show');
      overlay.__localweb_target__ = null;
    } catch (e) {}
  }

  function setVideoLoading(videoEl, on, reason) {
    try {
      if (!videoEl || !videoEl.classList) return;

      // The web app provides its own controls overlay. Prevent WebView native controls.
      try {
        if (videoEl.hasAttribute && videoEl.hasAttribute('controls')) {
          videoEl.removeAttribute('controls');
        }
        if (typeof videoEl.controls === 'boolean') videoEl.controls = false;
      } catch (e) {}

      if (on) {
        if (!videoEl.classList.contains('__localweb_loading')) {
          videoEl.classList.add('__localweb_loading');
          console.log('[media-debug] ui:video-loading=1 reason=' + (reason || ''));
        }
        // Cover the built-in play glyph while switching/loading.
        showLoadingOverlay(videoEl);
      } else {
        // Don't hide overlay / unhide the video yet. In WebView, "canplay/loadeddata" often
        // fires before playback actually starts, which causes a brief native overlay glyph.
        // We'll end the transition on "playing" or timeupdate.
        try { showLoadingOverlay(videoEl); } catch (e) {}
      }
    } catch (e) {}
  }

  function ensureVideoVisible(videoEl, reason) {
    try {
      if (!videoEl || videoEl.__localweb_forced_overlay__) return;
      // In soft fullscreen, avoid the fixed-position overlay workaround (it can make the
      // video surface disappear in Android WebView). Fullscreen styling should handle visibility.
      try {
        if (window.__localweb_soft_fullscreen_active__ || videoEl.__localweb_soft_fullscreen_active__) return;
      } catch (e) {}
      ensureBaseHeights();
      ensureFixStyleInstalled();
      scheduleVh('ensureVideoVisible');

      var rect = videoEl.getBoundingClientRect ? videoEl.getBoundingClientRect() : null;
      var h = rect ? Math.round(rect.height) : -1;
      if (h > 1) return;

      videoEl.__localweb_forced_overlay__ = true;
      videoEl.style.position = 'fixed';
      videoEl.style.left = '0';
      videoEl.style.top = '0';
      videoEl.style.width = '100vw';
      videoEl.style.height = '100vh';
      videoEl.style.maxWidth = '100vw';
      videoEl.style.maxHeight = '100vh';
      videoEl.style.objectFit = 'contain';
      videoEl.style.zIndex = '2147483647';
      videoEl.style.backgroundColor = 'black';
      videoEl.style.display = 'block';
      videoEl.style.visibility = 'visible';
      videoEl.style.opacity = '1';

      // Important: don't steal taps/clicks from the page's own controls (e.g., edge prev/next hit areas).
      // This overlay fix exists to make video visible in buggy WebView layouts; the web app should still
      // receive input events through it.
      try {
        if (videoEl.__localweb_prev_pointer_events__ === undefined) {
          videoEl.__localweb_prev_pointer_events__ = videoEl.style.pointerEvents;
        }
        videoEl.style.pointerEvents = 'none';
      } catch (e) {}

      console.log('[media-debug] applied video overlay fix:', 'reason=' + (reason || ''), 'src=' + (videoEl.currentSrc || videoEl.src || ''));
    } catch (e) {
      try { console.log('[media-debug] ensureVideoVisible failed:', safeToString(e && (e.message || e))); } catch (_e) {}
    }
  }

  function logMediaEvent(ev) {
    try {
      var el = ev && ev.target;
      if (!el || !el.tagName) return;
      var tag = String(el.tagName).toLowerCase();
      if (tag !== 'video' && tag !== 'audio') return;

      if (tag === 'video') {
        // Best-effort heuristic: hide native overlay controls while switching/loading.
        // Also hide the video element itself during source switches so the native play glyph can't show.
        if (ev.type === 'loadstart' || ev.type === 'emptied') {
          ensureFixStyleInstalled();
          beginVideoTransition(el, ev.type);
        }
        if (ev.type === 'waiting' || ev.type === 'stalled' || ev.type === 'seeking') {
          ensureFixStyleInstalled();
          setVideoLoading(el, true, ev.type);
        }
        if (ev.type === 'canplay' || ev.type === 'canplaythrough' || ev.type === 'loadeddata') {
          // Ready-ish, but keep overlay until we're actually running.
          setVideoLoading(el, false, ev.type);
        }
        if (ev.type === 'playing') {
          endVideoTransition(el, 'playing');
        }
        if (ev.type === 'timeupdate') {
          try {
            el.__localweb_last_time__ = (typeof el.currentTime === 'number') ? el.currentTime : el.__localweb_last_time__;
          } catch (e) {}
          endVideoTransition(el, 'timeupdate');
        }
      }

      var src = (el.currentSrc || el.src || '');
      var err = el.error;
      var errCode = err && typeof err.code === 'number' ? err.code : '';

      function dumpMediaState(reason) {
        try {
          // Throttle expensive state dumps.
          var now = Date.now();
          var last = el.__localweb_lastDumpTs__ || 0;
          if (reason === 'timeupdate' && (now - last) < 2000) return;
          el.__localweb_lastDumpTs__ = now;

          var rect = (el.getBoundingClientRect && el.getBoundingClientRect()) || null;
          var w = rect ? Math.round(rect.width) : -1;
          var h = rect ? Math.round(rect.height) : -1;

          var cs = (window.getComputedStyle && window.getComputedStyle(el)) || null;
          var display = cs ? cs.display : '';
          var visibility = cs ? cs.visibility : '';
          var opacity = cs ? cs.opacity : '';

          var vW = (tag === 'video' && typeof el.videoWidth === 'number') ? el.videoWidth : '';
          var vH = (tag === 'video' && typeof el.videoHeight === 'number') ? el.videoHeight : '';

          console.log(
            '[media-debug] state:',
            'reason=' + reason,
            'tag=' + tag,
            'src=' + src,
            'currentTime=' + (typeof el.currentTime === 'number' ? el.currentTime.toFixed(3) : ''),
            'duration=' + (typeof el.duration === 'number' ? el.duration.toFixed(3) : ''),
            'muted=' + (!!el.muted),
            'volume=' + (typeof el.volume === 'number' ? el.volume.toFixed(2) : ''),
            (tag === 'video' ? ('videoWH=' + vW + 'x' + vH) : ''),
            'rect=' + w + 'x' + h,
            'display=' + display,
            'visibility=' + visibility,
            'opacity=' + opacity
          );

          // If video decodes but is invisible due to layout collapse (h == 0),
          // apply a last-resort overlay fix so we can verify decoding/rendering.
          try {
            if (tag === 'video') {
              var vw = (typeof el.videoWidth === 'number') ? el.videoWidth : 0;
              var vh = (typeof el.videoHeight === 'number') ? el.videoHeight : 0;
              if (vw > 0 && vh > 0 && h <= 1) {
                ensureVideoVisible(el, reason);
              }
            }
          } catch (e) {}
        } catch (e) {}
      }

      console.log(
        '[media-debug] event:', ev.type,
        'src=' + src,
        'paused=' + (!!el.paused),
        'ended=' + (!!el.ended),
        'readyState=' + (el.readyState || 0),
        'networkState=' + (el.networkState || 0),
        (errCode !== '' ? ('errorCode=' + errCode) : '')
      );

      if (ev.type === 'loadedmetadata' || ev.type === 'playing' || ev.type === 'error') {
        dumpMediaState(ev.type);
      } else if (ev.type === 'timeupdate') {
        dumpMediaState('timeupdate');
      }
    } catch (e) {}
  }

  ['loadstart','emptied','loadeddata','play','playing','pause','waiting','stalled','error','loadedmetadata','canplay','canplaythrough','seeking','seeked','timeupdate']
    .forEach(function(t) { document.addEventListener(t, logMediaEvent, true); });

  // ---- Earlier detection: intercept source changes before loadstart ----
  try {
    if (!Element.prototype.__localweb_patched_setAttribute__) {
      Element.prototype.__localweb_patched_setAttribute__ = true;
      var __origSetAttribute = Element.prototype.setAttribute;
      Element.prototype.setAttribute = function(name, value) {
        try {
          var n = (name && String(name).toLowerCase()) || '';
          if (n === 'src' || n === 'poster') {
            var t = this && this.tagName ? String(this.tagName).toLowerCase() : '';
            if (t === 'video') {
              beginVideoTransition(this, 'setAttribute:' + n);
            } else if (t === 'source') {
              var v = this.closest && this.closest('video');
              if (v) beginVideoTransition(v, 'setAttribute:source.' + n);
            }
          }
        } catch (e) {}
        return __origSetAttribute.apply(this, arguments);
      };
    }
  } catch (e) {}

  // Some apps use property assignment (video.src = ...) which bypasses setAttribute.
  // Patch common setters + load() to start our transition BEFORE WebView paints native UI.
  (function patchMediaSrcSetters() {
    try {
      if (window.__localweb_patched_media_src__) return;
      window.__localweb_patched_media_src__ = true;

      function patchSetter(proto, propName, tagHint) {
        try {
          if (!proto) return;
          var desc = Object.getOwnPropertyDescriptor(proto, propName);
          if (!desc || typeof desc.set !== 'function' || typeof desc.get !== 'function') return;
          if (desc.set.__localweb_patched__) return;

          var origSet = desc.set;
          var origGet = desc.get;

          var newSet = function(v) {
            try {
              var t = (tagHint || (this && this.tagName ? String(this.tagName).toLowerCase() : ''));
              if (t === 'video') {
                beginVideoTransition(this, 'prop:' + propName);
              } else if (t === 'source') {
                var vv = this && this.closest ? this.closest('video') : null;
                if (vv) beginVideoTransition(vv, 'prop:source.' + propName);
              }
            } catch (e) {}
            return origSet.call(this, v);
          };
          try { newSet.__localweb_patched__ = true; } catch (e) {}

          Object.defineProperty(proto, propName, {
            configurable: true,
            enumerable: desc.enumerable,
            get: function() { return origGet.call(this); },
            set: newSet
          });
        } catch (e) {}
      }

      patchSetter(HTMLMediaElement && HTMLMediaElement.prototype, 'src', 'video');
      patchSetter(HTMLVideoElement && HTMLVideoElement.prototype, 'src', 'video');
      patchSetter(HTMLVideoElement && HTMLVideoElement.prototype, 'poster', 'video');
      patchSetter(HTMLSourceElement && HTMLSourceElement.prototype, 'src', 'source');

      try {
        if (HTMLMediaElement && HTMLMediaElement.prototype && !HTMLMediaElement.prototype.__localweb_patched_load__) {
          HTMLMediaElement.prototype.__localweb_patched_load__ = true;
          var __origLoad = HTMLMediaElement.prototype.load;
          if (typeof __origLoad === 'function') {
            HTMLMediaElement.prototype.load = function() {
              try {
                var t = (this && this.tagName ? String(this.tagName).toLowerCase() : '');
                if (t === 'video') beginVideoTransition(this, 'load()');
              } catch (e) {}
              return __origLoad.apply(this, arguments);
            };
          }
        }
      } catch (e) {}
    } catch (e) {}
  })();

  // Observe DOM mutations to catch frameworks that replace <video>/<source> nodes.
  function ensureInitialVideoVolume(videoEl) {
    try {
      if (!videoEl) return;
      if (videoEl.__localweb_initial_volume_set__) return;
      videoEl.__localweb_initial_volume_set__ = true;

      // User request: always start videos at volume=1.
      // Do not touch muted state, and do not do transition-time fades.
      try {
        if (typeof videoEl.volume === 'number') videoEl.volume = 1;
      } catch (e) {}
    } catch (e) {}
  }

  try {
    var mo = new MutationObserver(function(muts) {
      try {
        for (var i = 0; i < muts.length; i++) {
          var m = muts[i];
          if (!m) continue;
          if (m.type === 'attributes') {
            var attr = (m.attributeName && String(m.attributeName).toLowerCase()) || '';
            if (attr !== 'src' && attr !== 'poster') continue;
            var target = m.target;
            var tag = target && target.tagName ? String(target.tagName).toLowerCase() : '';
            if (tag === 'video') {
              beginVideoTransition(target, 'mo:attr.' + attr);
            } else if (tag === 'source') {
              var vv = target.closest && target.closest('video');
              if (vv) beginVideoTransition(vv, 'mo:attr.source.' + attr);
            }
          } else if (m.type === 'childList') {
            var nodes = m.addedNodes;
            if (!nodes || !nodes.length) continue;
            for (var j = 0; j < nodes.length; j++) {
              var node = nodes[j];
              if (!node || node.nodeType !== 1) continue;
              var nt = node.tagName ? String(node.tagName).toLowerCase() : '';
              if (nt === 'video') {
                ensureInitialVideoVolume(node);
                beginVideoTransition(node, 'mo:added.video');
              } else if (nt === 'source') {
                var vvv = node.closest && node.closest('video');
                if (vvv) beginVideoTransition(vvv, 'mo:added.source');
              } else {
                // If a subtree was added, check for nested videos.
                try {
                  if (node.querySelectorAll) {
                    var vids = node.querySelectorAll('video');
                    if (vids && vids.length) {
                      for (var k = 0; k < vids.length; k++) {
                        ensureInitialVideoVolume(vids[k]);
                        beginVideoTransition(vids[k], 'mo:added.subtree');
                      }
                    }
                  }
                } catch (e) {}
              }
            }
          }
        }
      } catch (e) {}
    });
    mo.observe(document.documentElement || document, {
      subtree: true,
      childList: true,
      attributes: true,
      attributeFilter: ['src', 'poster']
    });
  } catch (e) {}

  // Initial pass for any videos already on the page.
  try {
    var initialVids = document.querySelectorAll ? document.querySelectorAll('video') : null;
    if (initialVids && initialVids.length) {
      for (var iv = 0; iv < initialVids.length; iv++) ensureInitialVideoVolume(initialVids[iv]);
    }
  } catch (e) {}

  // ---- UI signals for host app (fullscreen + video taps) ----
  function logHostSignal(msg) {
    try { console.log('[media-debug] ui:' + msg); } catch (e) {}
  }

  // ---- Edge prev/next gesture helper (for apps that use large invisible edge hit targets) ----
  // Some WebView video rendering paths can prevent DOM overlay buttons from receiving pointer events.
  // This handler detects left/right edge taps by coordinates and programmatically clicks the app's
  // edge buttons if present.
  (function installEdgeNavGesture() {
    try {
      if (window.__localweb_edge_nav_installed__) return;
      window.__localweb_edge_nav_installed__ = true;

      function isInteractiveTarget(t) {
        try {
          if (!t || !t.closest) return false;
          return !!t.closest('button, input, select, textarea, a, [role="button"], [contenteditable="true"]');
        } catch (e) {}
        return false;
      }

      function getPointFromEvent(ev) {
        try {
          if (!ev) return null;
          if (ev.touches && ev.touches.length) {
            return { x: ev.touches[0].clientX, y: ev.touches[0].clientY };
          }
          if (typeof ev.clientX === 'number' && typeof ev.clientY === 'number') {
            return { x: ev.clientX, y: ev.clientY };
          }
        } catch (e) {}
        return null;
      }

      function clickEdgeButton(which) {
        try {
          var id = (which === 'prev') ? 'centerEdgePrevBtn' : 'centerEdgeNextBtn';
          var btn = document.getElementById(id);
          if (btn && typeof btn.click === 'function') {
            btn.click();
            return true;
          }
        } catch (e) {}
        return false;
      }

      function onEdgeGesture(ev) {
        try {
          var now = Date.now();
          if (window.__localweb_last_edge_nav_ts__ && (now - window.__localweb_last_edge_nav_ts__) < 350) return;

          var t = (ev && ev.target) ? ev.target : null;
          if (isInteractiveTarget(t)) return;

          // Only handle when interacting with the player surface.
          try {
            var pc = document.getElementById('player-container');
            if (pc && t && pc.contains && !pc.contains(t) && !window.__localweb_soft_fullscreen_active__) {
              return;
            }
          } catch (e) {}

          var p = getPointFromEvent(ev);
          if (!p) return;
          var w = (typeof window.innerWidth === 'number') ? window.innerWidth : 0;
          var h = (typeof window.innerHeight === 'number') ? window.innerHeight : 0;
          if (!w || !h) return;

          var size = Math.min(w * 0.30, h * 0.30);
          var cy = h / 2;
          var y0 = cy - (size / 2);
          var y1 = cy + (size / 2);
          if (!(p.y >= y0 && p.y <= y1)) return;

          var did = false;
          if (p.x <= size) {
            did = clickEdgeButton('prev');
          } else if (p.x >= (w - size)) {
            did = clickEdgeButton('next');
          }
          if (!did) return;

          window.__localweb_last_edge_nav_ts__ = now;
          try {
            if (ev && ev.cancelable) ev.preventDefault();
            if (typeof ev.stopImmediatePropagation === 'function') ev.stopImmediatePropagation();
            if (typeof ev.stopPropagation === 'function') ev.stopPropagation();
          } catch (e) {}
        } catch (e) {}
      }

      document.addEventListener('pointerdown', onEdgeGesture, { capture: true, passive: false });
      document.addEventListener('touchstart', onEdgeGesture, { capture: true, passive: false });
    } catch (e) {}
  })();

  // ---- Watchdog: recover from "video hidden" states after fullscreen toggles/transitions ----
  function forceShowVideoIfHidden(videoEl, reason) {
    try {
      if (!videoEl) return;

      // If our transition code left it hidden, bring it back.
      try {
        var cs = window.getComputedStyle ? window.getComputedStyle(videoEl) : null;
        var op = cs ? Number(cs.opacity) : null;
        var vis = cs ? String(cs.visibility || '') : '';
        if (vis === 'hidden' || (op !== null && isFinite(op) && op <= 0.01)) {
          videoEl.style.visibility = 'visible';
          videoEl.style.opacity = '1';
          try {
            if (videoEl.__localweb_prev_opacity__ !== undefined) videoEl.__localweb_prev_opacity__ = undefined;
            if (videoEl.__localweb_prev_visibility__ !== undefined) videoEl.__localweb_prev_visibility__ = undefined;
          } catch (e) {}
          try { if (videoEl.classList) videoEl.classList.remove('__localweb_loading'); } catch (e) {}
          try { hideLoadingOverlay(); } catch (e) {}
          try { console.log('[media-debug] watchdog: forced video visible:', reason || ''); } catch (e) {}
        }
      } catch (e) {}

      // If it has collapsed to 0 height, re-apply the known visibility workaround.
      try {
        var rect = videoEl.getBoundingClientRect ? videoEl.getBoundingClientRect() : null;
        var hh = rect ? Math.round(rect.height || 0) : 0;
        if (hh < 2) {
          ensureVideoVisible(videoEl, reason || 'watchdog');
        }
      } catch (e) {}
    } catch (e) {}
  }

  try {
    if (!window.__localweb_video_watchdog_installed__) {
      window.__localweb_video_watchdog_installed__ = true;
      setInterval(function() {
        try {
          var v = document.querySelector ? document.querySelector('video') : null;
          if (!v) return;
          // Only intervene if playback is active or the element is ready.
          var active = false;
          try { active = (!v.paused && !v.ended) || (v.readyState && v.readyState >= 2); } catch (e) {}
          if (!active) return;
          forceShowVideoIfHidden(v, 'interval');
        } catch (e) {}
      }, 900);
    }
  } catch (e) {}

  function preemptiveSilenceActiveVideo(reason) {
    try {
      var v = null;
      try { v = getPrimaryVideo && getPrimaryVideo(); } catch (e) {}
      if (!v) {
        try { v = document.querySelector && document.querySelector('video'); } catch (e) {}
      }
      if (!v) return;

      // If we are already in the middle of a transition, don't fight it.
      try {
        if (v.classList && v.classList.contains('__localweb_loading')) {
          return;
        }
      } catch (e) {}

      // Start the visual transition immediately so WebView's native overlay glyph can't flash.
      try { beginVideoTransition(v, reason || 'ui'); } catch (e) {}

      try { console.log('[media-debug] preemptive transition:', reason || ''); } catch (e) {}
    } catch (e) {}
  }

  // ---- Soft fullscreen (CSS-based; avoids WebView custom view swaps) ----
  // This keeps playback on the same <video> element to reduce/avoid A/V interruption.
  function getPrimaryVideo() {
    try {
      var vids = document.querySelectorAll ? document.querySelectorAll('video') : null;
      if (!vids || !vids.length) return null;
      // Prefer a currently-playing video.
      for (var i = 0; i < vids.length; i++) {
        var v = vids[i];
        try {
          if (v && !v.paused && !v.ended) return v;
        } catch (e) {}
      }
      return vids[0];
    } catch (e) {}
    return null;
  }

  function softFullscreenSetOverflow(on) {
    try {
      var de = document.documentElement;
      var body = document.body;
      if (on) {
        if (de && de.__localweb_prev_overflow__ === undefined) de.__localweb_prev_overflow__ = de.style.overflow;
        if (body && body.__localweb_prev_overflow__ === undefined) body.__localweb_prev_overflow__ = body.style.overflow;
        if (de) de.style.overflow = 'hidden';
        if (body) body.style.overflow = 'hidden';
      } else {
        if (de) {
          de.style.overflow = (de.__localweb_prev_overflow__ !== undefined) ? de.__localweb_prev_overflow__ : '';
          de.__localweb_prev_overflow__ = undefined;
        }
        if (body) {
          body.style.overflow = (body.__localweb_prev_overflow__ !== undefined) ? body.__localweb_prev_overflow__ : '';
          body.__localweb_prev_overflow__ = undefined;
        }
      }
    } catch (e) {}
  }

  function softFullscreenApply(videoEl) {
    try {
      if (!videoEl || !videoEl.style) return false;
      if (videoEl.__localweb_soft_fullscreen_active__) return true;

      // If transition logic hid it, unhide.
      try { setVideoHiddenForSwitch(videoEl, false); } catch (e) {}
      try { if (videoEl.classList) videoEl.classList.remove('__localweb_loading'); } catch (e) {}
      try { hideLoadingOverlay(); } catch (e) {}

      // In Android WebView, setting the VIDEO element itself to position:fixed can cause
      // the video surface to disappear (audio keeps playing). To avoid that, fullscreen
      // the wrapper (#player-container) and only size the video via width/height.
      var root = null;
      try { root = document.getElementById('player-container') || document.getElementById('player'); } catch (e) {}
      videoEl.__localweb_soft_fullscreen_root__ = root;

      if (root && root.style && root.__localweb_soft_fullscreen_prev_style__ === undefined) {
        root.__localweb_soft_fullscreen_prev_style__ = {
          position: root.style.position,
          left: root.style.left,
          top: root.style.top,
          right: root.style.right,
          bottom: root.style.bottom,
          width: root.style.width,
          height: root.style.height,
          maxWidth: root.style.maxWidth,
          maxHeight: root.style.maxHeight,
          zIndex: root.style.zIndex,
          backgroundColor: root.style.backgroundColor
        };
      }

      videoEl.__localweb_soft_fullscreen_prev_style__ = {
        width: videoEl.style.width,
        height: videoEl.style.height,
        maxWidth: videoEl.style.maxWidth,
        maxHeight: videoEl.style.maxHeight,
        objectFit: videoEl.style.objectFit,
        backgroundColor: videoEl.style.backgroundColor,
        display: videoEl.style.display,
        visibility: videoEl.style.visibility,
        opacity: videoEl.style.opacity,
        pointerEvents: videoEl.style.pointerEvents
      };

      softFullscreenSetOverflow(true);

      if (root && root.style) {
        root.style.position = 'absolute';
        root.style.left = '0';
        root.style.top = '0';
        root.style.right = '0';
        root.style.bottom = '0';
        root.style.width = '100vw';
        root.style.height = '100vh';
        root.style.maxWidth = '100vw';
        root.style.maxHeight = '100vh';
        root.style.zIndex = '2';
        root.style.backgroundColor = 'black';
      }

      // Size the video to fill the wrapper without moving it to fixed positioning.
      videoEl.style.width = '100%';
      videoEl.style.height = '100%';
      videoEl.style.maxWidth = '100%';
      videoEl.style.maxHeight = '100%';
      videoEl.style.objectFit = 'contain';
      videoEl.style.backgroundColor = 'black';
      videoEl.style.display = 'block';
      videoEl.style.visibility = 'visible';
      videoEl.style.opacity = '1';
      // Let overlay controls receive taps.
      videoEl.style.pointerEvents = 'none';

      videoEl.__localweb_soft_fullscreen_active__ = true;
      window.__localweb_soft_fullscreen_active__ = true;
      logHostSignal('fullscreen=1');
      return true;
    } catch (e) {}
    return false;
  }

  function softFullscreenRestore(videoEl) {
    try {
      if (!videoEl || !videoEl.style) return false;
      if (!videoEl.__localweb_soft_fullscreen_active__) return true;

      var prev = videoEl.__localweb_soft_fullscreen_prev_style__ || null;
      var root = videoEl.__localweb_soft_fullscreen_root__ || null;

      // Restore wrapper styles first.
      try {
        if (root && root.style && root.__localweb_soft_fullscreen_prev_style__) {
          var rp = root.__localweb_soft_fullscreen_prev_style__;
          root.style.position = rp.position || '';
          root.style.left = rp.left || '';
          root.style.top = rp.top || '';
          root.style.right = rp.right || '';
          root.style.bottom = rp.bottom || '';
          root.style.width = rp.width || '';
          root.style.height = rp.height || '';
          root.style.maxWidth = rp.maxWidth || '';
          root.style.maxHeight = rp.maxHeight || '';
          root.style.zIndex = rp.zIndex || '';
          root.style.backgroundColor = rp.backgroundColor || '';
        }
      } catch (e) {}
      try {
        if (root) root.__localweb_soft_fullscreen_prev_style__ = undefined;
      } catch (e) {}
      try { videoEl.__localweb_soft_fullscreen_root__ = null; } catch (e) {}

      if (prev) {
        videoEl.style.width = prev.width || '';
        videoEl.style.height = prev.height || '';
        videoEl.style.maxWidth = prev.maxWidth || '';
        videoEl.style.maxHeight = prev.maxHeight || '';
        videoEl.style.objectFit = prev.objectFit || '';
        videoEl.style.backgroundColor = prev.backgroundColor || '';
        videoEl.style.display = prev.display || '';
        videoEl.style.visibility = prev.visibility || '';
        videoEl.style.opacity = prev.opacity || '';
        videoEl.style.pointerEvents = prev.pointerEvents || '';
      } else {
        videoEl.style.width = '';
        videoEl.style.height = '';
        videoEl.style.maxWidth = '';
        videoEl.style.maxHeight = '';
        videoEl.style.objectFit = '';
        videoEl.style.backgroundColor = '';
        videoEl.style.display = '';
        videoEl.style.visibility = '';
        videoEl.style.opacity = '';
        videoEl.style.pointerEvents = '';
      }

      videoEl.__localweb_soft_fullscreen_prev_style__ = undefined;
      videoEl.__localweb_soft_fullscreen_active__ = false;
      window.__localweb_soft_fullscreen_active__ = false;
      softFullscreenSetOverflow(false);

      logHostSignal('fullscreen=0');
      return true;
    } catch (e) {}
    return false;
  }

  // Expose helpers for the Android host.
  window.__localweb_soft_fullscreen_enter__ = function() {
    try {
      var v = getPrimaryVideo();
      if (!v) return false;
      return softFullscreenApply(v);
    } catch (e) {}
    return false;
  };

  window.__localweb_soft_fullscreen_exit__ = function() {
    try {
      var v = getPrimaryVideo();
      if (!v) {
        // Still notify host.
        window.__localweb_soft_fullscreen_active__ = false;
        logHostSignal('fullscreen=0');
        return true;
      }
      return softFullscreenRestore(v);
    } catch (e) {}
    return false;
  };

  try {
    document.addEventListener('fullscreenchange', function() {
      try {
        var on = !!(document.fullscreenElement);
        logHostSignal('fullscreen=' + (on ? '1' : '0'));
        // Fullscreen transitions can leave the video hidden; recover proactively.
        try {
          var v = document.querySelector ? document.querySelector('video') : null;
          if (v) forceShowVideoIfHidden(v, 'fullscreenchange');
        } catch (e) {}
      } catch (e) {}
    }, true);
  } catch (e) {}

  function isVideoEl(t) {
    try {
      if (!t) return false;
      if (t.tagName && String(t.tagName).toLowerCase() === 'video') return true;
      if (t.closest && t.closest('video')) return true;
    } catch (e) {}
    return false;
  }

  ['pointerdown', 'touchstart', 'click']
    .forEach(function(t) {
      try {
        document.addEventListener(t, function(ev) {
          try {
            // Preemptive silence for known prev/next controls. Use capture-phase so we run
            // before the app swaps the media source.
            try {
              var el = ev && ev.target;
              var cur = el;
              while (cur && cur !== document.documentElement) {
                var id = cur && cur.id ? String(cur.id) : '';
                if (id === 'centerPrevBtn' || id === 'centerNextBtn' || id === 'centerEdgePrevBtn' || id === 'centerEdgeNextBtn') {
                  preemptiveSilenceActiveVideo('ui:' + id + ':' + t);
                  break;
                }
                cur = cur.parentElement;
              }
            } catch (e) {}

            if (isVideoEl(ev && ev.target)) {
              logHostSignal('video-touch');
            }
          } catch (e) {}
        }, true);
      } catch (e) {}
    });

  // Also preemptively silence on common keyboard navigation shortcuts.
  try {
    document.addEventListener('keydown', function(ev) {
      try {
        var k = ev && (ev.key || ev.code) ? String(ev.key || ev.code) : '';
        if (k === 'ArrowLeft' || k === 'ArrowRight' || k === 'MediaTrackPrevious' || k === 'MediaTrackNext') {
          preemptiveSilenceActiveVideo('key:' + k);
        }
      } catch (e) {}
    }, true);
  } catch (e) {}

  console.log('[media-debug] installed');
})();
