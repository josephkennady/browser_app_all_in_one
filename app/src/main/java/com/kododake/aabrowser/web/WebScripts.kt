/*
 * Copyright (C) 2025 AABrowser Contributors (https://github.com/kododake/AABrowser)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://gnu.org>.
 */

package com.kododake.aabrowser.web

object WebScripts {
    /** Runs at document start on YouTube origins only: prunes ad data from player responses and skips leftovers. */
    val YOUTUBE_ADBLOCK_JS = """
        (function() {
            if (window.__aabYtAdblock) return;
            window.__aabYtAdblock = true;
            const AD_KEYS = ['adPlacements', 'playerAds', 'adSlots', 'adBreakParams'];
            function prune(o, depth) {
                if (!o || typeof o !== 'object' || depth > 4) return o;
                for (const k of AD_KEYS) { if (k in o) { try { delete o[k]; } catch (e) { o[k] = undefined; } } }
                if (o.playerResponse) prune(o.playerResponse, depth + 1);
                if (Array.isArray(o.response)) o.response.forEach(function(r) { prune(r, depth + 1); });
                else if (o.response) prune(o.response, depth + 1);
                if (Array.isArray(o)) o.forEach(function(r) { prune(r, depth + 1); });
                return o;
            }
            try {
                const origParse = JSON.parse;
                JSON.parse = function() { return prune(origParse.apply(this, arguments), 0); };
                const origJson = Response.prototype.json;
                Response.prototype.json = function() { return origJson.apply(this, arguments).then(function(d) { return prune(d, 0); }); };
                let initial;
                Object.defineProperty(window, 'ytInitialPlayerResponse', {
                    configurable: true,
                    get: function() { return initial; },
                    set: function(v) { initial = prune(v, 0); }
                });
            } catch (e) {}
            const css = '.ytd-display-ad-renderer,.ytd-promoted-sparkles-web-renderer,.ytd-ad-slot-renderer,' +
                '#player-ads,#masthead-ad,ytd-merch-shelf-renderer,.ytp-ad-overlay-container,.ytp-ad-module,' +
                'ytd-in-feed-ad-layout-renderer,ytm-promoted-sparkles-web-renderer,ytm-companion-ad-renderer,' +
                '.ad-container,.ad-div{display:none!important}';
            function addCss() {
                if (document.getElementById('aab-yt-css')) return;
                const st = document.createElement('style');
                st.id = 'aab-yt-css';
                st.textContent = css;
                (document.head || document.documentElement).appendChild(st);
            }
            addCss();
            setInterval(function() {
                addCss();
                const skip = document.querySelector('.ytp-skip-ad-button,.ytp-ad-skip-button,.ytp-ad-skip-button-modern,.ytp-ad-skip-button-slot button');
                if (skip) skip.click();
                const player = document.querySelector('.html5-video-player.ad-showing,#movie_player.ad-showing');
                const video = document.querySelector('video');
                if (player && video && isFinite(video.duration) && video.duration > 0) {
                    video.muted = true;
                    video.playbackRate = 16;
                    video.currentTime = video.duration;
                }
            }, 300);
        })();
    """.trimIndent()

    val DRM_L3_ENFORCER_JS = """
        (function() {
            try {
                const host = window.location.hostname;
                if (host && (
                    host === 'youtube.com' || host.endsWith('.youtube.com') ||
                    host === 'googlevideo.com' || host.endsWith('.googlevideo.com') ||
                    host === 'youtube-nocookie.com' || host.endsWith('.youtube-nocookie.com')
                )) {
                    return;
                }
            } catch (_) {}

            if (typeof navigator === 'undefined' || !navigator.requestMediaKeySystemAccess) {
                return;
            }

            const patchKey = typeof Symbol !== 'undefined' && Symbol.for ? Symbol.for('__aab_drm_l3_enforced__') : '__aab_drm_l3_enforced__';
            if (navigator[patchKey]) return;

            const originalRequest = navigator.requestMediaKeySystemAccess;
            const patchedRequest = function requestMediaKeySystemAccess(keySystem, configs) {
                if (keySystem === 'com.widevine.alpha' && configs) {
                    try {
                        const newConfigs = Array.from(configs).map(config => {
                            const newConfig = Object.assign({}, config);
                            if (config.videoCapabilities) {
                                newConfig.videoCapabilities = Array.from(config.videoCapabilities).map(cap => {
                                    const newCap = Object.assign({}, cap);
                                    if (newCap.robustness && typeof newCap.robustness === 'string' && newCap.robustness.startsWith('HW_SECURE')) {
                                        newCap.robustness = 'SW_SECURE_DECODE';
                                    }
                                    return newCap;
                                });
                            }
                            if (config.audioCapabilities) {
                                newConfig.audioCapabilities = Array.from(config.audioCapabilities).map(cap => {
                                    const newCap = Object.assign({}, cap);
                                    if (newCap.robustness && typeof newCap.robustness === 'string' && newCap.robustness.startsWith('HW_SECURE')) {
                                        newCap.robustness = 'SW_SECURE_DECODE';
                                    }
                                    return newCap;
                                });
                            }
                            return newConfig;
                        });
                        return originalRequest.call(navigator, keySystem, newConfigs).catch(function() {
                            return originalRequest.call(navigator, keySystem, configs);
                        });
                    } catch (_) {
                        return originalRequest.call(navigator, keySystem, configs);
                    }
                }
                return originalRequest.call(navigator, keySystem, configs);
            };

            try {
                Object.defineProperty(patchedRequest, 'name', { value: 'requestMediaKeySystemAccess' });
                patchedRequest.toString = function() {
                    return 'function requestMediaKeySystemAccess() { [native code] }';
                };
            } catch (_) {}

            try {
                Object.defineProperty(navigator, patchKey, { value: true, enumerable: false, configurable: false });
                Object.defineProperty(navigator, 'requestMediaKeySystemAccess', {
                    value: patchedRequest,
                    writable: true,
                    configurable: true
                });
            } catch (_) {
                navigator.requestMediaKeySystemAccess = patchedRequest;
            }
        })();
    """.trimIndent()
}
