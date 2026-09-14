/**
 * Vynox download page.
 *
 * The download button is never hard-coded to a version-specific URL: this script
 * asks the GitHub API for the newest release and points the button at its APK.
 * If the API is unreachable, the button keeps its fallback href (the releases
 * page), so the page can never end up linking to a stale or broken file.
 */
(function () {
  var API = "https://api.github.com/repos/pushparaj9749/Vynox/releases/latest";
  var RELEASES_PAGE = "https://github.com/pushparaj9749/Vynox/releases/latest";

  var download = document.getElementById("download");
  var badge = document.getElementById("version-badge");
  var meta = document.getElementById("release-meta");
  var changelog = document.getElementById("changelog-body");

  function formatBytes(bytes) {
    if (!bytes) return "";
    var mb = bytes / (1024 * 1024);
    return mb >= 1 ? (mb.toFixed(1) + " MB") : (Math.round(bytes / 1024) + " KB");
  }

  function formatDate(iso) {
    if (!iso) return "";
    var d = new Date(iso);
    if (isNaN(d.getTime())) return iso;
    return d.toLocaleDateString(undefined, { year: "numeric", month: "long", day: "numeric" });
  }

  /** Extremely small, safe markdown renderer for release notes. */
  function renderMarkdown(md) {
    if (!md) return "";
    var out = [];
    var lines = md.replace(/\r\n/g, "\n").split("\n");
    var listOpen = false;
    lines.forEach(function (raw) {
      var line = raw
        .replace(/&/g, "&amp;")
        .replace(/</g, "&lt;")
        .replace(/>/g, "&gt;")
        .replace(/`([^`]+)`/g, "<code>$1</code>")
        .replace(/\*\*([^*]+)\*\*/g, "<strong>$1</strong>");
      if (/^\s*[-*]\s+/.test(line)) {
        if (!listOpen) { out.push("<ul>"); listOpen = true; }
        out.push("<li>" + line.replace(/^\s*[-*]\s+/, "") + "</li>");
        return;
      }
      if (listOpen) { out.push("</ul>"); listOpen = false; }
      if (/^#{1,6}\s+/.test(line)) {
        var text = line.replace(/^#{1,6}\s+/, "");
        if (text.toLowerCase() === "changelog" || text.toLowerCase() === "what's changed") return;
        out.push("<h3>" + text + "</h3>");
        return;
      }
      if (line.trim() === "") return;
      out.push("<p>" + line + "</p>");
    });
    if (listOpen) out.push("</ul>");
    return out.join("");
  }

  fetch(API, { headers: { Accept: "application/vnd.github+json" }, cache: "no-store" })
    .then(function (res) {
      if (!res.ok) throw new Error("HTTP " + res.status);
      return res.json();
    })
    .then(function (release) {
      var version = release.tag_name || release.name || "latest";
      var apk = (release.assets || []).filter(function (asset) {
        return /\.apk$/i.test(asset.name || "");
      })[0];

      if (badge) badge.textContent = "Latest release · " + version;

      if (apk && apk.browser_download_url) {
        if (download) {
          download.href = apk.browser_download_url;
          download.setAttribute("data-dynamic", "1");
          download.setAttribute("download", apk.name || "");
          download.textContent = "Download APK · " + version;
        }
        if (meta) {
          var bits = [apk.name, formatBytes(apk.size), "published " + formatDate(release.published_at)];
          if (apk.download_count != null) bits.push(apk.download_count.toLocaleString() + " downloads");
          meta.textContent = bits.filter(Boolean).join(" · ");
        }
      } else {
        if (download) download.href = RELEASES_PAGE;
        if (meta) {
          meta.textContent =
            "Latest release " + version + " · " + formatDate(release.published_at) +
            " · APK not attached yet — opening the release page instead.";
        }
      }

      if (changelog) {
        var html = renderMarkdown(release.body || "");
        changelog.innerHTML = html
          ? "<h3>" + version + "</h3>" + html
          : "<p>No release notes were published for " + version + ".</p>";
      }
    })
    .catch(function () {
      // Offline, rate limited or API unavailable: keep the static fallback link.
      if (download) download.href = RELEASES_PAGE;
      if (badge) badge.textContent = "Vynox for Android";
      if (meta) meta.textContent = "Release information could not be loaded. You can still download the newest APK from GitHub Releases.";
      if (changelog) {
        changelog.innerHTML =
          "<p>Release notes could not be loaded right now. See " +
          '<a href="https://github.com/pushparaj9749/Vynox/releases" target="_blank" rel="noopener">GitHub Releases</a>' +
          " for the full changelog.</p>";
      }
    });
})();
