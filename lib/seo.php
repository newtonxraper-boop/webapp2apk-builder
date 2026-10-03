<?php
/**
 * lib/seo.php - SEO title, meta description and favicon, editable from
 * admin.php (stored in the "settings" table).
 *
 * Usage inside a page's <head>, in place of the hard-coded <title>:
 *
 *   require_once __DIR__ . '/lib/seo.php'; w2a_seo_head('Log in');
 *   (called from inside a php open/close tag pair)
 *
 * - $pageTitle is optional. With a page title and a saved SEO title the
 *   result is "Log in - Your SEO title". Without a page title (home page)
 *   the SEO title is used on its own.
 * - If nothing is saved yet, the page's fallback title is used, so existing
 *   pages look exactly as before until you fill in the admin form.
 * - Pass ['noindex' => true] for private pages (admin, my apps, build).
 */

require_once __DIR__ . '/../db.php';

function w2a_seo_settings($refresh = false) {
    static $cache = null;
    if ($cache !== null && !$refresh) return $cache;

    $cache = ['seo_title' => '', 'seo_description' => '', 'seo_favicon' => ''];
    try {
        $rows = w2a_db()->query(
            "SELECT setting_key, setting_value FROM settings
             WHERE setting_key IN ('seo_title','seo_description','seo_favicon')"
        )->fetchAll(PDO::FETCH_KEY_PAIR);
        $cache = array_merge($cache, $rows);
    } catch (Throwable $e) {
        // Never let a settings problem break a page.
    }
    return $cache;
}

function w2a_seo_base_url() {
    $scheme = (!empty($_SERVER['HTTPS']) && $_SERVER['HTTPS'] !== 'off') ? 'https' : 'http';
    $host = $_SERVER['HTTP_HOST'] ?? '';
    return $host !== '' ? $scheme . '://' . $host : '';
}

/** Public URL of the saved favicon (with cache-buster), or '' if none. */
function w2a_seo_favicon_url() {
    $s = w2a_seo_settings();
    $rel = $s['seo_favicon'];
    if ($rel === '') return '';
    $file = __DIR__ . '/../' . $rel;
    $v = is_file($file) ? filemtime($file) : time();
    return '/' . ltrim($rel, '/') . '?v=' . $v;
}

function w2a_seo_head($pageTitle = '', array $opts = []) {
    $s = w2a_seo_settings();
    $e = function ($v) { return htmlspecialchars((string) $v, ENT_QUOTES, 'UTF-8'); };

    $seoTitle = trim($s['seo_title']);
    $desc     = trim($s['seo_description']);

    if ($seoTitle !== '' && $pageTitle !== '') {
        $title = $pageTitle . ' - ' . $seoTitle;
    } elseif ($seoTitle !== '') {
        $title = $seoTitle;
    } else {
        $title = $pageTitle !== '' ? $pageTitle : 'Website to APK';
    }

    echo '<title>' . $e($title) . "</title>\n";

    if ($desc !== '') {
        echo '<meta name="description" content="' . $e($desc) . "\">\n";
    }

    if (!empty($opts['noindex'])) {
        echo "<meta name=\"robots\" content=\"noindex, nofollow\">\n";
    } else {
        // Open Graph / Twitter, so shared links look right.
        echo '<meta property="og:type" content="website">' . "\n";
        echo '<meta property="og:title" content="' . $e($title) . "\">\n";
        if ($desc !== '') {
            echo '<meta property="og:description" content="' . $e($desc) . "\">\n";
        }
        $base = w2a_seo_base_url();
        $uri  = strtok($_SERVER['REQUEST_URI'] ?? '/', '?');
        if ($base !== '') {
            echo '<link rel="canonical" href="' . $e($base . $uri) . "\">\n";
            echo '<meta property="og:url" content="' . $e($base . $uri) . "\">\n";
        }
        echo '<meta name="twitter:card" content="summary">' . "\n";
    }

    $fav = w2a_seo_favicon_url();
    if ($fav !== '') {
        $ext = strtolower(pathinfo(parse_url($fav, PHP_URL_PATH), PATHINFO_EXTENSION));
        $types = [
            'png' => 'image/png', 'ico' => 'image/x-icon', 'svg' => 'image/svg+xml',
            'webp' => 'image/webp', 'jpg' => 'image/jpeg', 'jpeg' => 'image/jpeg',
        ];
        $type = $types[$ext] ?? 'image/png';
        echo '<link rel="icon" type="' . $type . '" href="' . $e($fav) . "\">\n";
        echo '<link rel="apple-touch-icon" href="' . $e($fav) . "\">\n";
    }
}
