/*
 * Offline document engine. The app loads this page in a WebView with network loads blocked and no
 * JavaScript interface, starts a job with ZC.start(...) through evaluateJavascript, polls ZC.status(id)
 * and reads the base64 result in chunks with ZC.chunk(...). Libraries ship inside the APK (lib/).
 */
(function () {
  'use strict';

  // The engine's WebView is never on screen, where timers can be throttled to once a second. The zip
  // writers inside these libraries yield with setTimeout(fn, 0) / setImmediate between files, so run those
  // through a MessageChannel, which is not throttled.
  var nativeSetTimeout = window.setTimeout.bind(window);
  var channel = new MessageChannel();
  var queue = [];
  channel.port1.onmessage = function () {
    var fn = queue.shift();
    if (fn) fn();
  };
  function soon(fn, args) {
    queue.push(function () { fn.apply(null, args); });
    channel.port2.postMessage(0);
  }
  window.setTimeout = function (fn, ms) {
    if (typeof fn === 'function' && !(ms > 0)) {
      soon(fn, Array.prototype.slice.call(arguments, 2));
      return 0;
    }
    return nativeSetTimeout.apply(null, arguments);
  };
  window.setImmediate = function (fn) {
    soon(fn, Array.prototype.slice.call(arguments, 1));
    return 0;
  };

  var LIBS = {
    pdf: ['lib/pdfmake.min.js', 'lib/vfs_fonts.js', 'lib/fonts-indic.js', 'lib/html-to-pdfmake.js'],
    docx: ['lib/docx.js', 'lib/docx-math.js', 'lib/docx-html.js'],
    xlsx: ['lib/xlsx.full.min.js'],
    pptx: ['lib/pptxgen.bundle.js']
  };
  var loaded = {};
  var jobs = {};

  function loadScript(src) {
    return new Promise(function (resolve, reject) {
      var s = document.createElement('script');
      s.src = src;
      s.onload = resolve;
      s.onerror = function () { reject(new Error('Could not load ' + src)); };
      document.head.appendChild(s);
    });
  }

  function load(kind) {
    if (!loaded[kind]) {
      loaded[kind] = LIBS[kind].reduce(function (p, src) {
        return p.then(function () { return loadScript(src); });
      }, Promise.resolve());
    }
    return loaded[kind];
  }

  /** Parses the agent's HTML, drops anything that would need the network, and returns the body's HTML. */
  function cleanHtml(html) {
    var doc = new DOMParser().parseFromString(String(html || ''), 'text/html');
    doc.querySelectorAll('script,iframe,object,embed,video,audio,link,meta,noscript').forEach(function (e) { e.remove(); });
    doc.querySelectorAll('img').forEach(function (img) {
      var src = img.getAttribute('src') || '';
      if (src.indexOf('data:') !== 0) {
        var alt = img.getAttribute('alt');
        if (alt) img.replaceWith(doc.createTextNode('[' + alt + ']')); else img.remove();
      }
    });
    var styles = Array.prototype.map.call(doc.querySelectorAll('style'), function (s) { return s.textContent; }).join('\n');
    doc.querySelectorAll('style').forEach(function (e) { e.remove(); });
    return { title: (doc.title || '').trim(), body: doc.body ? doc.body.innerHTML : '', css: styles };
  }

  var builders = {
    pdf: function (spec) {
      var page = cleanHtml(spec.html);
      var content = htmlToPdfmake(page.body, {
        window: window,
        tableAutoSize: true,
        defaultStyles: {
          h1: { fontSize: 22, bold: true, marginBottom: 8, marginTop: 4 },
          h2: { fontSize: 17, bold: true, marginBottom: 6, marginTop: 10 },
          h3: { fontSize: 14, bold: true, marginBottom: 5, marginTop: 8 },
          h4: { fontSize: 12, bold: true, marginBottom: 4, marginTop: 6 },
          p: { margin: [0, 3, 0, 6] },
          a: { color: '#1f5fbf', decoration: 'underline' },
          th: { bold: true, fillColor: '#eef1ea' },
          code: { background: '#f1f3ef', fontSize: 10 },
          pre: { background: '#f1f3ef', fontSize: 10, margin: [0, 4, 0, 8] }
        }
      });
      useScriptFonts(content);
      var title = spec.title || page.title || 'Document';
      var dd = {
        info: { title: title, creator: 'ZeroClaw Mobile' },
        pageSize: spec.landscape ? { width: 841.89, height: 595.28 } : 'A4',
        pageMargins: [48, 52, 48, 56],
        content: content,
        defaultStyle: { fontSize: 11, lineHeight: 1.25 },
        footer: function (current, count) {
          return count > 1 ? { text: current + ' / ' + count, alignment: 'center', fontSize: 9, color: '#777777', margin: [0, 18, 0, 0] } : null;
        }
      };
      return pdfMake.createPdf(dd).getBase64();
    },

    docx: function (spec) {
      var page = cleanHtml(spec.html);
      var children = docxHtml.htmlToContent(page.body, { stylesheet: page.css || undefined });
      var doc = new docx.Document({
        title: spec.title || page.title || 'Document',
        creator: 'ZeroClaw Mobile',
        styles: { default: { document: { run: { font: 'Calibri', size: 22 } } } },
        sections: [{
          properties: spec.landscape ? { page: { size: { orientation: docx.PageOrientation.LANDSCAPE } } } : {},
          children: children.length ? children : [new docx.Paragraph('')]
        }]
      });
      return docx.Packer.toBase64String(doc);
    },

    xlsx: function (spec) {
      var sheets = spec.sheets || [];
      if (!sheets.length) throw new Error('No sheets given');
      var wb = XLSX.utils.book_new();
      var used = {};
      sheets.forEach(function (sheet, i) {
        var rows = (sheet.rows || []).map(function (row) {
          return (row || []).map(cellValue);
        });
        var ws = XLSX.utils.aoa_to_sheet(rows);
        // Column widths from the longest value in each column (max 60 characters).
        var widths = [];
        rows.forEach(function (row) {
          row.forEach(function (v, c) {
            var len = v && typeof v === 'object' ? 12 : String(v == null ? '' : v).length;
            widths[c] = Math.min(60, Math.max(widths[c] || 8, len + 2));
          });
        });
        ws['!cols'] = widths.map(function (w) { return { wch: w || 8 }; });
        var name = String(sheet.name || ('Sheet' + (i + 1))).replace(/[\[\]\*\?\/\\:]/g, ' ').slice(0, 31) || ('Sheet' + (i + 1));
        while (used[name.toLowerCase()]) name = name.slice(0, 28) + ' ' + (i + 1);
        used[name.toLowerCase()] = true;
        XLSX.utils.book_append_sheet(wb, ws, name);
      });
      return XLSX.write(wb, { type: 'base64', bookType: 'xlsx', compression: true });
    },

    pptx: function (spec) {
      var pptx = new PptxGenJS();
      pptx.layout = 'LAYOUT_WIDE'; // 13.33 x 7.5 in
      pptx.title = spec.title || 'Presentation';
      pptx.author = 'ZeroClaw Mobile';
      var ink = '0F100D', accent = '3D7A4E', paper = 'FFFFFF', muted = '5E625A';
      var font = 'Calibri';

      if (spec.title) {
        var cover = pptx.addSlide();
        cover.background = { color: 'DDE1DA' };
        cover.addText(spec.title, { x: 0.8, y: 2.4, w: 11.7, h: 1.4, fontFace: font, fontSize: 40, bold: true, color: ink });
        if (spec.subtitle) {
          cover.addText(spec.subtitle, { x: 0.8, y: 3.8, w: 11.7, h: 0.8, fontFace: font, fontSize: 20, color: muted });
        }
      }

      (spec.slides || []).forEach(function (s) {
        var slide = pptx.addSlide();
        slide.background = { color: paper };
        var y = 0.5;
        if (s.title) {
          slide.addText(s.title, { x: 0.6, y: y, w: 12.1, h: 0.9, fontFace: font, fontSize: 30, bold: true, color: ink });
          slide.addShape(pptx.ShapeType.rect, { x: 0.6, y: y + 0.95, w: 1.2, h: 0.06, fill: { color: accent }, line: { color: accent } });
          y += 1.3;
        }
        var bottom = 7.0;
        var hasTable = s.table && s.table.length;
        var textH = hasTable ? 1.6 : bottom - y;
        if (s.text) {
          slide.addText(s.text, { x: 0.6, y: y, w: 12.1, h: Math.min(textH, 1.4), fontFace: font, fontSize: 18, color: ink, valign: 'top' });
          y += Math.min(textH, 1.4) + 0.1;
          textH = hasTable ? 1.4 : bottom - y;
        }
        if (s.bullets && s.bullets.length) {
          var items = s.bullets.map(function (b) {
            return { text: String(b), options: { bullet: { indent: 18 }, breakLine: true } };
          });
          var h = Math.max(0.8, Math.min(textH, bottom - y));
          slide.addText(items, { x: 0.6, y: y, w: 12.1, h: h, fontFace: font, fontSize: s.bullets.length > 7 ? 16 : 20, color: ink, valign: 'top', paraSpaceAfter: 8 });
          y += hasTable ? h + 0.1 : h;
        }
        if (hasTable) {
          var rows = s.table.map(function (row, r) {
            return (row || []).map(function (cell) {
              return { text: String(cell == null ? '' : cell), options: r === 0 ? { bold: true, fill: { color: 'EEF1EA' } } : {} };
            });
          });
          slide.addTable(rows, { x: 0.6, y: y, w: 12.1, fontFace: font, fontSize: 14, color: ink, border: { type: 'solid', pt: 0.75, color: 'C9CEC5' }, autoPage: true });
        }
        if (s.notes) slide.addNotes(String(s.notes));
      });
      return pptx.write({ outputType: 'base64' });
    }
  };

  // pdfmake's Roboto has no Indian scripts: runs of Tamil or Devanagari text get a Noto font of their own.
  var SCRIPT_FONTS = [
    { font: 'NotoTamil', re: /[\u0B80-\u0BFF]/, file: 'noto-tamil' },
    { font: 'NotoDevanagari', re: /[\u0900-\u097F\uA8E0-\uA8FF\u1CD0-\u1CFF]/, file: 'noto-devanagari' }
  ];
  var fontsAdded = false;

  function scriptFontOf(ch) {
    for (var i = 0; i < SCRIPT_FONTS.length; i++) if (SCRIPT_FONTS[i].re.test(ch)) return SCRIPT_FONTS[i].font;
    return null;
  }

  /** Splits a string into runs by script; null when it is all Latin (nothing to change). */
  function splitRuns(str) {
    if (!SCRIPT_FONTS.some(function (f) { return f.re.test(str); })) return null;
    var runs = [], cur = null, buf = '';
    for (var ch of str) {
      var f = scriptFontOf(ch);
      // Spaces and joiners stay in the current run; punctuation uses Roboto (the script fonts lack it).
      if (f === null && /[\s\u200C\u200D]/.test(ch) && cur !== null) f = cur;
      if (f !== cur && buf) { runs.push(cur ? { text: buf, font: cur } : buf); buf = ''; }
      cur = f;
      buf += ch;
    }
    if (buf) runs.push(cur ? { text: buf, font: cur } : buf);
    return runs;
  }

  function useScriptFonts(node) {
    if (!fontsAdded) {
      var defs = {};
      SCRIPT_FONTS.forEach(function (f) {
        defs[f.font] = { normal: f.file + '-400.woff', bold: f.file + '-700.woff', italics: f.file + '-400.woff', bolditalics: f.file + '-700.woff' };
      });
      pdfMake.addFonts(defs);
      fontsAdded = true;
    }
    walk(node);
    function walk(n) {
      if (Array.isArray(n)) {
        for (var i = 0; i < n.length; i++) {
          if (typeof n[i] === 'string') { var r = splitRuns(n[i]); if (r) n[i] = { text: r }; } else walk(n[i]);
        }
        return;
      }
      if (!n || typeof n !== 'object') return;
      if (typeof n.text === 'string') { var runs = splitRuns(n.text); if (runs) n.text = runs; } else if (n.text) walk(n.text);
      ['stack', 'ul', 'ol', 'columns'].forEach(function (k) { if (n[k]) walk(n[k]); });
      if (n.table && n.table.body) n.table.body.forEach(walk);
    }
  }

  /** Numbers and booleans stay typed; strings starting with "=" become formulas. */
  function cellValue(v) {
    if (typeof v === 'string') {
      var t = v.trim();
      if (t.charAt(0) === '=' && t.length > 1) return { t: 'n', f: t.slice(1) };
      if (/^-?\d+(\.\d+)?$/.test(t) && t.length < 16 && !/^-?0\d/.test(t)) return Number(t);
      if (t === 'true' || t === 'false') return t === 'true';
    }
    return v;
  }

  window.ZC = {
    start: function (id, kind, spec) {
      jobs[id] = { state: 'running' };
      if (!builders[kind]) {
        jobs[id] = { state: 'error', error: 'Unknown format ' + kind };
        return 'ok';
      }
      load(kind)
        .then(function () { return builders[kind](spec || {}); })
        .then(function (b64) {
          if (typeof b64 !== 'string' || !b64.length) throw new Error('Empty output');
          jobs[id] = { state: 'done', data: b64 };
        })
        .catch(function (e) {
          jobs[id] = { state: 'error', error: String((e && e.message) || e) };
        });
      return 'ok';
    },
    status: function (id) {
      var j = jobs[id];
      if (!j) return JSON.stringify({ state: 'missing' });
      return JSON.stringify({ state: j.state, length: j.data ? j.data.length : 0, error: j.error || null });
    },
    chunk: function (id, offset, size) {
      var j = jobs[id];
      return j && j.data ? j.data.substr(offset, size) : '';
    },
    drop: function (id) {
      delete jobs[id];
      return 'ok';
    }
  };
})();
