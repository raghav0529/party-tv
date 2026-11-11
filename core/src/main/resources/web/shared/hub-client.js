/*
 * Keeps a page on the active app, so a choice on any phone or the remote moves every screen together.
 * ES5 only. Navigates only after a successful poll shows a different app, so a failed poll or a bad reply
 * can never cause a redirect or a loop. Polling carries on through every error.
 */
// TV pages only, before their own fit() runs. A 19.2rem stage at the default 100px is wider than the WebView, which then
// zooms out and reports an inflated innerWidth, so fit() would leave the page about 1.5x too big and cut off.
if (location.pathname === "/tv" || location.pathname.indexOf("/tv/") === 0) {
    document.documentElement.style.fontSize = "10px";
}

var HubClient = (function () {
    var readyStarted = false;
    var POLL_MS = 1000;
    var REQUEST_TIMEOUT_MS = 4000;
    var NAVIGATE_RETRY_MS = 5000;

    var kind = "phone";
    var app = null;
    var timer = null;
    var pollRequest = null;
    var pollStartedAt = 0;
    var navigatedAt = 0;

    function pageFor(active) {
        if (active === null) return kind === "tv" ? "/tv" : "/";
        return (kind === "tv" ? "/tv/" : "/phone/") + encodeURIComponent(active);
    }

    function applyReply(text) {
        var reply = JSON.parse(text);
        var active = reply.active;
        if (active !== null && typeof active !== "string") return;
        if (active === app) return;
        // A slow load must not be restarted by the next poll, but a navigation that went nowhere is tried again.
        var t = new Date().getTime();
        if (navigatedAt && t - navigatedAt < NAVIGATE_RETRY_MS) return;
        navigatedAt = t;
        location.replace(pageFor(active));
    }

    function poll() {
        try {
            var t = new Date().getTime();
            if (pollRequest) {
                if (t - pollStartedAt < REQUEST_TIMEOUT_MS) return;
                var stuck = pollRequest;
                pollRequest = null;
                try {
                    stuck.abort();
                } catch (e) {}
            }
            var xhr = new XMLHttpRequest();
            pollRequest = xhr;
            pollStartedAt = t;
            xhr.open("GET", "/api/hub", true);
            xhr.onreadystatechange = function () {
                if (xhr.readyState !== 4 || pollRequest !== xhr) return;
                pollRequest = null;
                if (xhr.status !== 200) return;
                try {
                    applyReply(xhr.responseText);
                } catch (e) {}
            };
            xhr.send();
        } catch (e) {
            pollRequest = null;
        }
    }

    function post(path, body, done) {
        var finished = false;
        function finish(ok) {
            if (finished) return;
            finished = true;
            if (ok) poll();
            if (done) {
                try {
                    done(ok);
                } catch (e) {}
            }
        }
        try {
            var xhr = new XMLHttpRequest();
            xhr.open("POST", path, true);
            xhr.setRequestHeader("Content-Type", "application/json");
            xhr.timeout = REQUEST_TIMEOUT_MS;
            xhr.ontimeout = function () {
                finish(false);
            };
            xhr.onreadystatechange = function () {
                if (xhr.readyState === 4) finish(xhr.status === 200);
            };
            xhr.send(body);
        } catch (e) {
            finish(false);
        }
    }

    return {
        /** options: {kind: "tv" or "phone", app: null or an app id}. */
        /** A TV page calls this once its first real data is drawn, which reveals the page. Safe to call often. */
        ready: function () {
            var b = document.body;
            if (!b || readyStarted) { return; }
            readyStarted = true;
            function reveal() {
                if (!/(^| )ptv-ready( |$)/.test(b.className)) { b.className = (b.className ? b.className + " " : "") + "ptv-ready"; }
            }
            // Pictures such as the QR code arrive a moment after the data. Wait for them briefly so nothing pops in empty.
            // Checked on the next tick, because the page sets its picture sources just after calling ready().
            setTimeout(function () {
            var imgs = document.images, pending = 0, i;
            function done() { pending--; if (pending <= 0) { reveal(); } }
            for (i = 0; i < imgs.length; i++) {
                if (imgs[i].getAttribute("src") && !imgs[i].complete) {
                    pending++;
                    imgs[i].addEventListener("load", done);
                    imgs[i].addEventListener("error", done);
                }
            }
            if (pending === 0) { reveal(); }
            }, 0);
            setTimeout(reveal, 700);
        },
        start: function (options) {
            options = options || {};
            kind = options.kind === "tv" ? "tv" : "phone";
            app = typeof options.app === "string" ? options.app : null;
            navigatedAt = 0;
            if (timer !== null) clearInterval(timer);
            timer = setInterval(poll, POLL_MS);
            poll();
        },
        /** Moves everyone to this app. done(ok), if given, is called with whether the server accepted. */
        open: function (id, done) {
            post("/api/hub/open", JSON.stringify({id: id}), done);
        },
        /** Moves everyone to the home screen. done(ok), if given, is called with whether the server accepted. */
        home: function (done) {
            post("/api/hub/home", "{}", done);
        }
    };
})();
