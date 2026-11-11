/*
 * Copyright (C) 2026 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

function resolveRendererDir() {
  const candidates = [
    path.resolve(__dirname, '../../main/resources/com/android/build/gradle/internal/test/report/renderer'),
    path.resolve(process.cwd(), 'src/main/resources/com/android/build/gradle/internal/test/report/renderer'),
    process.env.TEST_SRCDIR
      ? path.resolve(
          process.env.TEST_SRCDIR,
          '_main/tools/base/build-system/gradle-core/src/main/resources/com/android/build/gradle/internal/test/report/renderer'
        )
      : '',
  ].filter(Boolean);

  for (const dir of candidates) {
    if (fs.existsSync(path.join(dir, 'index.html'))) {
      return dir;
    }
  }
  throw new Error(`Could not locate renderer resources in: ${candidates.join(', ')}`);
}

const RENDERER_DIR = resolveRendererDir();
const INDEX_HTML = fs.readFileSync(path.join(RENDERER_DIR, 'index.html'), 'utf8');
const SCRIPT_FILES = [
  'suites/suite-registry.js',
  'suites/screenshot.js',
  'suites/backup.js',
  'detail-view.js',
  'script.js',
];

function decodeEntities(str) {
  return String(str)
    .replace(/&lt;/g, '<')
    .replace(/&gt;/g, '>')
    .replace(/&quot;/g, '"')
    .replace(/&#0*39;/g, "'")
    .replace(/&#x27;/gi, "'")
    .replace(/&amp;/g, '&');
}

function encodeEntities(str) {
  return String(str)
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;');
}

function toCamelKey(attrName) {
  return attrName
    .slice(5)
    .replace(/-([a-z0-9])/gi, (_, ch) => ch.toUpperCase());
}

function toDataAttr(propName) {
  return 'data-' + String(propName).replace(/[A-Z]/g, ch => '-' + ch.toLowerCase());
}

const VOID_TAGS = new Set([
  'area', 'base', 'br', 'col', 'embed', 'hr', 'img', 'input',
  'link', 'meta', 'param', 'source', 'track', 'wbr',
]);

class MiniTextNode {
  constructor(text, ownerDocument) {
    this.nodeType = 3;
    this.nodeValue = text;
    this.parentNode = null;
    this.parentElement = null;
    this.ownerDocument = ownerDocument;
  }

  get textContent() {
    return this.nodeValue;
  }

  set textContent(val) {
    this.nodeValue = String(val ?? '');
  }
}

class MiniClassList {
  constructor(el) {
    this._el = el;
  }

  _tokens() {
    const raw = this._el._attrs.get('class') || '';
    return raw.split(/\s+/).filter(Boolean);
  }

  _write(tokens) {
    this._el._attrs.set('class', tokens.join(' '));
  }

  add(...names) {
    const set = new Set(this._tokens());
    for (const n of names) {
      if (n) set.add(String(n));
    }
    this._write([...set]);
  }

  remove(...names) {
    const removeSet = new Set(names.map(String));
    this._write(this._tokens().filter(t => !removeSet.has(t)));
  }

  contains(name) {
    return this._tokens().includes(String(name));
  }

  toggle(name, force) {
    const key = String(name);
    const has = this.contains(key);
    const shouldHave = force === undefined ? !has : Boolean(force);
    if (shouldHave && !has) this.add(key);
    if (!shouldHave && has) this.remove(key);
    return shouldHave;
  }
}

function splitTopLevelCommas(selector) {
  const parts = [];
  let depth = 0;
  let quote = null;
  let start = 0;
  for (let i = 0; i < selector.length; i++) {
    const ch = selector[i];
    if (quote) {
      if (ch === quote && selector[i - 1] !== '\\') quote = null;
      continue;
    }
    if (ch === '"' || ch === "'") {
      quote = ch;
    } else if (ch === '[' || ch === '(') {
      depth++;
    } else if (ch === ']' || ch === ')') {
      depth = Math.max(0, depth - 1);
    } else if (ch === ',' && depth === 0) {
      parts.push(selector.slice(start, i).trim());
      start = i + 1;
    }
  }
  const tail = selector.slice(start).trim();
  if (tail) parts.push(tail);
  return parts;
}

function tokenizeCombinators(selector) {
  const tokens = [];
  let buf = '';
  let depth = 0;
  let quote = null;
  for (let i = 0; i < selector.length; i++) {
    const ch = selector[i];
    if (quote) {
      buf += ch;
      if (ch === quote && selector[i - 1] !== '\\') quote = null;
      continue;
    }
    if (ch === '"' || ch === "'") {
      quote = ch;
      buf += ch;
    } else if (ch === '[' || ch === '(') {
      depth++;
      buf += ch;
    } else if (ch === ']' || ch === ')') {
      depth = Math.max(0, depth - 1);
      buf += ch;
    } else if (depth === 0 && (ch === '>' || /\s/.test(ch))) {
      if (buf) {
        tokens.push({ type: 'compound', value: buf });
        buf = '';
      }
      let comb = ch === '>' ? '>' : ' ';
      while (i + 1 < selector.length && (selector[i + 1] === '>' || /\s/.test(selector[i + 1]))) {
        i++;
        if (selector[i] === '>') comb = '>';
      }
      if (tokens.length > 0) {
        tokens.push({ type: 'combinator', value: comb });
      }
    } else {
      buf += ch;
    }
  }
  if (buf) tokens.push({ type: 'compound', value: buf });
  return tokens;
}

function matchesCompound(el, compound) {
  if (!el || el.nodeType !== 1) return false;
  let rest = compound;

  // Handle :not(...) clauses
  while (true) {
    const notIdx = rest.indexOf(':not(');
    if (notIdx === -1) break;
    let depth = 0;
    let endIdx = -1;
    for (let i = notIdx + 4; i < rest.length; i++) {
      if (rest[i] === '(') depth++;
      else if (rest[i] === ')') {
        depth--;
        if (depth === 0) {
          endIdx = i;
          break;
        }
      }
    }
    if (endIdx === -1) break;
    const inner = rest.slice(notIdx + 5, endIdx).trim();
    if (matchesSelector(el, inner)) return false;
    rest = rest.slice(0, notIdx) + rest.slice(endIdx + 1);
  }

  // Handle [attr...] clauses
  while (true) {
    const openIdx = rest.indexOf('[');
    if (openIdx === -1) break;
    const closeIdx = rest.indexOf(']', openIdx + 1);
    if (closeIdx === -1) break;
    const rawAttr = rest.slice(openIdx + 1, closeIdx).trim();
    const eqIdx = rawAttr.indexOf('=');
    if (eqIdx === -1) {
      if (!el.hasAttribute(rawAttr)) return false;
    } else {
      const attrName = rawAttr.slice(0, eqIdx).trim();
      let expected = rawAttr.slice(eqIdx + 1).trim();
      if ((expected.startsWith('"') && expected.endsWith('"')) || (expected.startsWith("'") && expected.endsWith("'"))) {
        expected = expected.slice(1, -1);
      }
      if (el.getAttribute(attrName) !== expected) return false;
    }
    rest = rest.slice(0, openIdx) + rest.slice(closeIdx + 1);
  }

  rest = rest.trim();
  if (!rest || rest === '*') return true;

  const parts = rest.match(/^[a-zA-Z0-9_-]+|#[a-zA-Z0-9_.:-]+|\.[a-zA-Z0-9_-]+/g);
  if (!parts || parts.join('') !== rest) return false;

  for (const token of parts) {
    if (token.startsWith('#')) {
      if (el.id !== token.slice(1)) return false;
    } else if (token.startsWith('.')) {
      if (!el.classList.contains(token.slice(1))) return false;
    } else {
      if (el.tagName.toLowerCase() !== token.toLowerCase()) return false;
    }
  }
  return true;
}

function matchesChain(el, tokens, tokenIdx) {
  if (!matchesCompound(el, tokens[tokenIdx].value)) return false;
  if (tokenIdx === 0) return true;
  const comb = tokens[tokenIdx - 1].value;
  if (comb === '>') {
    return Boolean(el.parentElement && matchesChain(el.parentElement, tokens, tokenIdx - 2));
  }
  let anc = el.parentElement;
  while (anc) {
    if (matchesChain(anc, tokens, tokenIdx - 2)) return true;
    anc = anc.parentElement;
  }
  return false;
}

function matchesSelector(el, selector) {
  const groups = splitTopLevelCommas(selector);
  for (const group of groups) {
    const tokens = tokenizeCombinators(group);
    if (tokens.length > 0 && matchesChain(el, tokens, tokens.length - 1)) {
      return true;
    }
  }
  return false;
}

class MiniElement {
  constructor(tagName, ownerDocument) {
    this.nodeType = 1;
    this.tagName = String(tagName || 'DIV').toUpperCase();
    this.ownerDocument = ownerDocument;
    this.parentNode = null;
    this.parentElement = null;
    this.childNodes = [];
    this._attrs = new Map();
    this._listeners = new Map();
    this.classList = new MiniClassList(this);
    this.style = {
      _props: new Map(),
      setProperty(k, v) {
        this._props.set(k, String(v));
      },
      getPropertyValue(k) {
        return this._props.get(k) || '';
      },
    };
    this.scrollTop = 0;
    this.scrollLeft = 0;
    this.scrollWidth = 100;
    this.clientWidth = 200;
    this._value = undefined;
    this._checked = undefined;

    const self = this;
    this.dataset = new Proxy(
      {},
      {
        get(_, prop) {
          if (typeof prop !== 'string') return undefined;
          const attr = toDataAttr(prop);
          return self._attrs.has(attr) ? self._attrs.get(attr) : undefined;
        },
        set(_, prop, val) {
          if (typeof prop === 'string') {
            self._attrs.set(toDataAttr(prop), String(val));
          }
          return true;
        },
        deleteProperty(_, prop) {
          if (typeof prop === 'string') {
            self._attrs.delete(toDataAttr(prop));
          }
          return true;
        },
        has(_, prop) {
          return typeof prop === 'string' && self._attrs.has(toDataAttr(prop));
        },
        ownKeys() {
          const keys = [];
          for (const k of self._attrs.keys()) {
            if (k.startsWith('data-')) keys.push(toCamelKey(k));
          }
          return keys;
        },
        getOwnPropertyDescriptor(_, prop) {
          if (typeof prop === 'string' && self._attrs.has(toDataAttr(prop))) {
            return { enumerable: true, configurable: true, value: self._attrs.get(toDataAttr(prop)) };
          }
          return undefined;
        },
      }
    );
  }

  get children() {
    return this.childNodes.filter(n => n.nodeType === 1);
  }

  get firstElementChild() {
    return this.children[0] || null;
  }

  get id() {
    return this._attrs.get('id') || '';
  }

  set id(val) {
    this.setAttribute('id', val);
  }

  get className() {
    return this._attrs.get('class') || '';
  }

  set className(val) {
    this.setAttribute('class', val);
  }

  get src() {
    return this._attrs.get('src') || '';
  }

  set src(val) {
    this.setAttribute('src', val);
  }

  get href() {
    return this._attrs.get('href') || '';
  }

  set href(val) {
    this.setAttribute('href', val);
  }

  get value() {
    if (this._value !== undefined) return this._value;
    return this._attrs.get('value') || '';
  }

  set value(val) {
    this._value = String(val ?? '');
  }

  get checked() {
    if (this._checked !== undefined) return this._checked;
    return this._attrs.has('checked');
  }

  set checked(val) {
    this._checked = Boolean(val);
  }

  get disabled() {
    return this._attrs.has('disabled');
  }

  set disabled(val) {
    if (val) this._attrs.set('disabled', '');
    else this._attrs.delete('disabled');
  }

  setAttribute(name, val) {
    this._attrs.set(String(name).toLowerCase(), String(val ?? ''));
  }

  getAttribute(name) {
    const key = String(name).toLowerCase();
    return this._attrs.has(key) ? this._attrs.get(key) : null;
  }

  hasAttribute(name) {
    return this._attrs.has(String(name).toLowerCase());
  }

  removeAttribute(name) {
    this._attrs.delete(String(name).toLowerCase());
  }

  appendChild(child) {
    if (!child) return child;
    if (child.parentNode) {
      child.parentNode.removeChild(child);
    }
    child.parentNode = this;
    child.parentElement = this;
    this.childNodes.push(child);

    if (
      child.nodeType === 1 &&
      child.tagName === 'SCRIPT' &&
      child.src &&
      this.ownerDocument &&
      typeof this.ownerDocument._onScriptAppended === 'function'
    ) {
      this.ownerDocument._onScriptAppended(child);
    }
    return child;
  }

  removeChild(child) {
    const idx = this.childNodes.indexOf(child);
    if (idx !== -1) {
      this.childNodes.splice(idx, 1);
      child.parentNode = null;
      child.parentElement = null;
    }
    return child;
  }

  remove() {
    if (this.parentNode) {
      this.parentNode.removeChild(this);
    }
  }

  contains(other) {
    let cur = other;
    while (cur) {
      if (cur === this) return true;
      cur = cur.parentNode;
    }
    return false;
  }

  matches(selector) {
    return matchesSelector(this, selector);
  }

  closest(selector) {
    let cur = this;
    while (cur && cur.nodeType === 1) {
      if (matchesSelector(cur, selector)) return cur;
      cur = cur.parentElement;
    }
    return null;
  }

  querySelector(selector) {
    const stack = [...this.children];
    while (stack.length > 0) {
      const node = stack.shift();
      if (matchesSelector(node, selector)) return node;
      if (node.children.length > 0) {
        stack.unshift(...node.children);
      }
    }
    return null;
  }

  querySelectorAll(selector) {
    const out = [];
    const visit = (el) => {
      for (const child of el.children) {
        if (matchesSelector(child, selector)) out.push(child);
        visit(child);
      }
    };
    visit(this);
    return out;
  }

  get textContent() {
    let out = '';
    for (const child of this.childNodes) {
      out += child.textContent;
    }
    return out;
  }

  set textContent(val) {
    for (const c of this.childNodes) {
      c.parentNode = null;
      c.parentElement = null;
    }
    this.childNodes = [];
    const str = String(val ?? '');
    if (str.length > 0) {
      this.appendChild(new MiniTextNode(str, this.ownerDocument));
    }
  }

  get innerHTML() {
    return this.childNodes.map(serializeNode).join('');
  }

  set innerHTML(html) {
    for (const c of this.childNodes) {
      c.parentNode = null;
      c.parentElement = null;
    }
    this.childNodes = [];
    parseHTMLInto(this, String(html ?? ''), this.ownerDocument);
  }

  addEventListener(type, fn) {
    if (typeof fn !== 'function') return;
    if (!this._listeners.has(type)) this._listeners.set(type, []);
    this._listeners.get(type).push(fn);
  }

  removeEventListener(type, fn) {
    const arr = this._listeners.get(type);
    if (!arr) return;
    const idx = arr.indexOf(fn);
    if (idx !== -1) arr.splice(idx, 1);
  }

  dispatchEvent(rawEvent) {
    const event = {
      bubbles: true,
      defaultPrevented: false,
      _stopped: false,
      ...rawEvent,
      target: rawEvent.target || this,
      currentTarget: this,
      preventDefault() {
        this.defaultPrevented = true;
      },
      stopPropagation() {
        this._stopped = true;
      },
    };

    let cur = this;
    while (cur) {
      event.currentTarget = cur;
      const listeners = cur._listeners && cur._listeners.get(event.type);
      if (listeners) {
        for (const fn of [...listeners]) {
          fn.call(cur, event);
        }
      }
      if (!event.bubbles || event._stopped) break;
      cur = cur.parentNode;
    }
    return !event.defaultPrevented;
  }

  click() {
    this.dispatchEvent({ type: 'click', bubbles: true });
  }

  focus() {
    if (this.ownerDocument) {
      this.ownerDocument.activeElement = this;
    }
  }

  select() {}

  getBoundingClientRect() {
    return { top: 20, left: 20, bottom: 60, right: 220, width: 200, height: 40 };
  }
}

function serializeNode(node) {
  if (node.nodeType === 3) {
    return encodeEntities(node.nodeValue);
  }
  const tag = node.tagName.toLowerCase();
  const attrs = [];
  for (const [k, v] of node._attrs.entries()) {
    attrs.push(v === '' ? k : `${k}="${encodeEntities(v)}"`);
  }
  const open = attrs.length > 0 ? `<${tag} ${attrs.join(' ')}>` : `<${tag}>`;
  if (VOID_TAGS.has(tag)) return open;
  return `${open}${node.childNodes.map(serializeNode).join('')}</${tag}>`;
}

function parseAttributes(attrText, el) {
  const attrRegex = /([a-zA-Z_:][a-zA-Z0-9_.:-]*)(?:\s*=\s*(?:"([^"]*)"|'([^']*)'|([^\s"'=<>`]+)))?/g;
  let match;
  while ((match = attrRegex.exec(attrText)) !== null) {
    const name = match[1].toLowerCase();
    const rawVal = match[2] !== undefined ? match[2] : (match[3] !== undefined ? match[3] : (match[4] !== undefined ? match[4] : ''));
    el.setAttribute(name, decodeEntities(rawVal));
  }
}

function parseHTMLInto(rootEl, html, ownerDocument) {
  const cleaned = html.replace(/<!--[\s\S]*?-->/g, '').replace(/<!DOCTYPE[^>]*>/gi, '');
  const tagRegex = /<\/?([a-zA-Z][a-zA-Z0-9:-]*)(\s[^>]*?)?\s*(\/?)>/g;
  const stack = [rootEl];
  let lastIdx = 0;
  let match;

  while ((match = tagRegex.exec(cleaned)) !== null) {
    if (match.index > lastIdx) {
      const text = decodeEntities(cleaned.slice(lastIdx, match.index));
      if (text) {
        stack[stack.length - 1].appendChild(new MiniTextNode(text, ownerDocument));
      }
    }
    lastIdx = tagRegex.lastIndex;

    const full = match[0];
    const tagLower = match[1].toLowerCase();
    const attrPart = match[2] || '';
    const selfClosing = match[3] === '/' || VOID_TAGS.has(tagLower);
    const isClosing = full.startsWith('</');

    if (isClosing) {
      for (let i = stack.length - 1; i > 0; i--) {
        if (stack[i].tagName.toLowerCase() === tagLower) {
          stack.length = i;
          break;
        }
      }
      continue;
    }

    const el = new MiniElement(tagLower, ownerDocument);
    if (attrPart) parseAttributes(attrPart, el);
    stack[stack.length - 1].appendChild(el);

    if (tagLower === 'script' || tagLower === 'style') {
      const closePattern = new RegExp(`</${tagLower}\\s*>`, 'ig');
      closePattern.lastIndex = tagRegex.lastIndex;
      const closeMatch = closePattern.exec(cleaned);
      if (closeMatch) {
        const rawInner = cleaned.slice(tagRegex.lastIndex, closeMatch.index);
        if (rawInner) el.appendChild(new MiniTextNode(rawInner, ownerDocument));
        tagRegex.lastIndex = closePattern.lastIndex;
        lastIdx = tagRegex.lastIndex;
      }
      continue;
    }

    if (!selfClosing) {
      stack.push(el);
    }
  }

  if (lastIdx < cleaned.length) {
    const tail = decodeEntities(cleaned.slice(lastIdx));
    if (tail) {
      stack[stack.length - 1].appendChild(new MiniTextNode(tail, ownerDocument));
    }
  }
}

class MiniDocument {
  constructor(html, onScriptAppended) {
    this.nodeType = 9;
    this._listeners = new Map();
    this._onScriptAppended = onScriptAppended;
    this.documentElement = new MiniElement('html', this);
    this.documentElement.parentNode = this;
    parseHTMLInto(this.documentElement, html, this);
    this.head = this.documentElement.querySelector('head') || new MiniElement('head', this);
    this.body = this.documentElement.querySelector('body') || new MiniElement('body', this);
    const titleEl = this.documentElement.querySelector('title');
    this.title = titleEl ? titleEl.textContent : 'Test Report';
    this.activeElement = this.body;
  }

  createElement(tag) {
    return new MiniElement(tag, this);
  }

  createTextNode(text) {
    return new MiniTextNode(String(text ?? ''), this);
  }

  getElementById(id) {
    return this.documentElement.querySelector(`#${id}`);
  }

  querySelector(selector) {
    return this.documentElement.querySelector(selector);
  }

  querySelectorAll(selector) {
    return this.documentElement.querySelectorAll(selector);
  }

  addEventListener(type, fn) {
    if (!this._listeners.has(type)) this._listeners.set(type, []);
    this._listeners.get(type).push(fn);
  }

  removeEventListener(type, fn) {
    const arr = this._listeners.get(type);
    if (!arr) return;
    const idx = arr.indexOf(fn);
    if (idx !== -1) arr.splice(idx, 1);
  }

  dispatchEvent(rawEvent) {
    const event = {
      bubbles: false,
      defaultPrevented: false,
      ...rawEvent,
      target: rawEvent.target || this,
      currentTarget: this,
      preventDefault() {
        this.defaultPrevented = true;
      },
      stopPropagation() {},
    };
    const arr = this._listeners.get(event.type) || [];
    for (const fn of [...arr]) {
      fn.call(this, event);
    }
    return !event.defaultPrevented;
  }

  execCommand() {
    return true;
  }
}

function createReportHarness(testDataSource, lazyDetails = {}) {
  let clipboardText = '';
  const historyStack = [{ state: null, url: 'file:///report/index.html' }];
  let historyIndex = 0;

  let sandbox;
  const document = new MiniDocument(INDEX_HTML, (scriptEl) => {
    const src = scriptEl.src || '';
    queueMicrotask(() => {
      const match = /^details\/([^/]+)\.js$/.exec(src);
      const id = match ? decodeURIComponent(match[1]) : null;
      if (id && Object.prototype.hasOwnProperty.call(lazyDetails, id)) {
        sandbox.window.__AGP_TEST_DETAILS = sandbox.window.__AGP_TEST_DETAILS || {};
        sandbox.window.__AGP_TEST_DETAILS[id] = lazyDetails[id];
        if (typeof scriptEl.onload === 'function') scriptEl.onload();
      } else if (typeof scriptEl.onerror === 'function') {
        scriptEl.onerror();
      }
    });
  });

  const windowObj = {
    document,
    console,
    innerWidth: 1280,
    innerHeight: 800,
    scrollY: 0,
    location: {
      href: 'file:///report/index.html',
      pathname: '/report/index.html',
      search: '',
      hash: '',
    },
    navigator: {
      clipboard: {
        writeText(str) {
          clipboardText = String(str);
          return Promise.resolve();
        },
      },
    },
    history: {
      get state() {
        return historyStack[historyIndex] ? historyStack[historyIndex].state : null;
      },
      pushState(state, _title, url) {
        historyStack.splice(historyIndex + 1);
        historyStack.push({ state: structuredClone(state), url: url || windowObj.location.href });
        historyIndex = historyStack.length - 1;
        if (url && String(url).includes('#')) {
          windowObj.location.hash = '#' + String(url).split('#')[1];
        } else {
          windowObj.location.hash = '';
        }
      },
      replaceState(state, _title, url) {
        historyStack[historyIndex] = { state: structuredClone(state), url: url || windowObj.location.href };
      },
      back() {
        this.go(-1);
      },
      go(delta) {
        const nextIndex = Math.max(0, Math.min(historyStack.length - 1, historyIndex + delta));
        if (nextIndex !== historyIndex) {
          historyIndex = nextIndex;
          const entryUrl = historyStack[historyIndex].url || '';
          windowObj.location.hash = String(entryUrl).includes('#') ? '#' + String(entryUrl).split('#')[1] : '';
          windowObj._dispatch('popstate', { state: structuredClone(historyStack[historyIndex].state) });
        }
      },
    },
    _listeners: new Map(),
    addEventListener(type, fn) {
      if (!this._listeners.has(type)) this._listeners.set(type, []);
      this._listeners.get(type).push(fn);
    },
    removeEventListener(type, fn) {
      const arr = this._listeners.get(type);
      if (!arr) return;
      const idx = arr.indexOf(fn);
      if (idx !== -1) arr.splice(idx, 1);
    },
    _dispatch(type, extra = {}) {
      const target = sandbox || windowObj;
      const arr = this._listeners.get(type) || [];
      const evt = { type, preventDefault() {}, stopPropagation() {}, ...extra };
      for (const fn of [...arr]) fn.call(target, evt);
      const propHandler = target[`on${type}`] || this[`on${type}`];
      if (typeof propHandler === 'function') propHandler.call(target, evt);
    },
    scrollTo(_x, y) {
      this.scrollY = y || 0;
    },
    requestAnimationFrame(cb) {
      cb();
      return 1;
    },
    setTimeout,
    clearTimeout,
    URL,
    URLSearchParams,
    Map,
    Set,
    Object,
    Array,
    String,
    Number,
    Boolean,
    Math,
    JSON,
    RegExp,
    Promise,
    Error,
    encodeURIComponent,
    decodeURIComponent,
  };

  sandbox = vm.createContext({
    ...windowObj,
    window: windowObj,
    self: windowObj,
    TEST_DATA_SOURCE: structuredClone(testDataSource),
  });
  sandbox.globalThis = sandbox;
  sandbox.window = sandbox;

  for (const relPath of SCRIPT_FILES) {
    const absPath = path.join(RENDERER_DIR, relPath);
    const code = fs.readFileSync(absPath, 'utf8');
    vm.runInContext(code, sandbox, { filename: absPath });
  }

  document.dispatchEvent({ type: 'DOMContentLoaded' });

  return {
    sandbox,
    document,
    app: sandbox.TestReportApp,
    UIUtils: sandbox.UIUtils,
    SuiteRegistry: sandbox.SuiteRegistry,
    getClipboardText: () => clipboardText,
    flushAsync: () => new Promise(resolve => setImmediate(resolve)),
  };
}

function summary(suiteName, variantName, { total = 1, pass = 0, fail = 0, skipped = 0, durationMs = 100 } = {}) {
  const relevant = pass + fail;
  const rate = relevant > 0 ? Math.round((pass / relevant) * 100) : 0;
  return {
    name: suiteName,
    variantSummaries: [
      {
        name: variantName,
        total,
        passed: pass,
        failed: fail,
        skipped,
        rate,
        durationMs,
      },
    ],
  };
}

test('SuiteRegistry resolves built-in plugins and UIUtils.sanitizeUrl enforces path safety', () => {
  const report = {
    projectName: ':app',
    modules: [],
  };
  const { SuiteRegistry, UIUtils } = createReportHarness(report);

  assert.equal(SuiteRegistry.resolve('backupRestore').id, 'backupRestore');
  assert.equal(SuiteRegistry.resolve('backupRestore').useRichSingleSuiteList, true);
  assert.equal(SuiteRegistry.resolve('screenshot').id, 'screenshot');
  assert.equal(SuiteRegistry.resolve('screenshot').useRichSingleSuiteList, false);
  assert.equal(SuiteRegistry.resolve('unknownCustomAdapter').id, 'default');
  assert.equal(SuiteRegistry.resolve('unknownCustomAdapter').useRichSingleSuiteList, false);

  // Relative report artifact paths pass; dot segments, traversal, backslashes, and dangerous schemes are rejected.
  assert.equal(UIUtils.sanitizeUrl('artifacts/t_0001/host-log.txt', false), 'artifacts/t_0001/host-log.txt');
  assert.equal(UIUtils.sanitizeUrl('artifacts/env_01/backup_rules.xml', false), 'artifacts/env_01/backup_rules.xml');
  assert.equal(UIUtils.sanitizeUrl('./artifacts/env_01/backup_rules.xml', false), '');
  assert.equal(UIUtils.sanitizeUrl('../secret.txt', false), '');
  assert.equal(UIUtils.sanitizeUrl('artifacts/%2e%2e/secret.txt', false), '');
  // Double-encoded %252e%252e decodes once to literal "%2e%2e" and is re-encoded safely without traversal.
  assert.equal(UIUtils.sanitizeUrl('artifacts/%252e%252e/secret.txt', false), 'artifacts/%252e%252e/secret.txt');
  assert.equal(UIUtils.sanitizeUrl('artifacts\\host-log.txt', false), '');
  assert.equal(UIUtils.sanitizeUrl('/etc/passwd', false), '');
  assert.equal(UIUtils.sanitizeUrl('//evil.example/payload', false), '');
  assert.equal(UIUtils.sanitizeUrl('javascript:alert(1)', true), '');
  assert.equal(UIUtils.sanitizeUrl('data:text/html,<h1>x</h1>', true), '');
  assert.equal(UIUtils.sanitizeUrl('file:///etc/passwd', true), '');
  assert.equal(UIUtils.sanitizeUrl('https://developer.android.com/guide', false), '');
  assert.equal(UIUtils.sanitizeUrl('https://developer.android.com/guide', true), 'https://developer.android.com/guide');
});

test('UnitTest single-suite report preserves classic 3-stat header, 4-column matrix, and classic stack trace view', () => {
  const unitReport = {
    projectName: ':app',
    variants: ['debug'],
    testSuites: ['unitTest'],
    targets: ['default'],
    numberOfModules: 1,
    numberOfPackages: 1,
    numberOfClasses: 1,
    suiteDescriptors: {
      unitTest: {
        adapterId: 'default',
        title: 'Unit Test',
        summaryColumns: [],
      },
    },
    suiteEnvironments: [
      {
        envId: 'env_01',
        suiteName: 'unitTest',
        variantName: 'debug',
        modulePath: ':app',
        applicationId: 'com.example.app',
      },
    ],
    modules: [
      {
        name: ':app',
        testSuiteSummaries: [summary('unitTest', 'debug', { total: 2, pass: 1, fail: 1, durationMs: 45 })],
        packages: [
          {
            name: 'com.example',
            testSuiteSummaries: [summary('unitTest', 'debug', { total: 2, pass: 1, fail: 1, durationMs: 45 })],
            classes: [
              {
                name: 'CalculatorTest',
                testSuiteSummaries: [summary('unitTest', 'debug', { total: 2, pass: 1, fail: 1, durationMs: 45 })],
                testCases: [
                  {
                    name: 'addsNumbers',
                    targets: [
                      {
                        name: 'default',
                        testSuiteSummaries: [summary('unitTest', 'debug', { total: 1, pass: 1, durationMs: 15 })],
                        testSuiteResults: [
                          {
                            testSuiteName: 'unitTest',
                            variantResults: {
                              debug: {
                                status: 'pass',
                                durationMs: 15,
                                adapterId: 'default',
                                properties: { customNote: 'kept-out-of-rich-check' },
                              },
                            },
                          },
                        ],
                      },
                    ],
                  },
                  {
                    name: 'dividesByZero',
                    targets: [
                      {
                        name: 'default',
                        testSuiteSummaries: [summary('unitTest', 'debug', { total: 1, fail: 1, durationMs: 30 })],
                        commonStackTraces: [
                          {
                            id: 'st_1',
                            stackTrace: 'java.lang.ArithmeticException: / by zero\n\tat com.example.CalculatorTest.dividesByZero(CalculatorTest.kt:42)',
                            occurrences: { unitTest: ['debug'] },
                          },
                        ],
                        testSuiteResults: [
                          {
                            testSuiteName: 'unitTest',
                            variantResults: {
                              debug: {
                                status: 'fail',
                                durationMs: 30,
                                stackTraceId: 'st_1',
                                detailId: 't_0001',
                                adapterId: 'default',
                              },
                            },
                          },
                        ],
                      },
                    ],
                  },
                ],
              },
            ],
          },
        ],
      },
    ],
  };

  const { document, app } = createReportHarness(unitReport, {
    t_0001: { systemOut: 'captured stdout' },
  });

  // Classic header and toolbar invariants
  assert.equal(app.usesRichSingleSuiteLayout(), false);
  assert.equal(document.getElementById('header-stats-multi').classList.contains('hidden'), false);
  assert.equal(document.getElementById('header-stats-single').classList.contains('hidden'), true);
  assert.equal(document.getElementById('suite-env-bar').classList.contains('hidden'), true);
  assert.equal(document.getElementById('status-segments').classList.contains('hidden'), true);

  // Classic 2-row variant matrix header (colspan="4", no Duration column)
  const headerRows = document.getElementById('table-headers').querySelectorAll('tr');
  assert.equal(headerRows.length, 2);
  const variantGroupTh = headerRows[0].querySelector('th[colspan="4"]');
  assert.ok(variantGroupTh, 'Expected classic colspan="4" variant header');
  assert.equal(variantGroupTh.textContent.trim(), 'debug');
  const subHeaderLabels = headerRows[1].querySelectorAll('th span[data-info-key]').map(s => s.textContent.trim());
  assert.deepEqual(subHeaderLabels, ['Pass', 'Fail', 'Skip', 'Pass Rate']);

  // Passing unit test is NOT clickable; failed unit test IS clickable
  const rows = document.getElementById('results-data').querySelectorAll('tr');
  assert.equal(rows.length, 2);
  const failTrigger = document.getElementById('results-data').querySelector('[data-test-case="dividesByZero"]');
  const passTrigger = document.getElementById('results-data').querySelector('[data-test-case="addsNumbers"]');
  assert.ok(failTrigger, 'Expected failed unit test to be clickable');
  assert.equal(passTrigger, null, 'Expected passing unit test without rich entries not to be clickable');

  // Clicking failed unit test opens classic stack trace view (not RichTestDetailView)
  failTrigger.click();
  assert.equal(app.state.detailViewMode, 'classic');
  assert.match(document.title, /^Stack Trace: dividesByZero - /);
  assert.equal(document.getElementById('copy-context-btn'), null);
  assert.equal(document.getElementById('stack-trace-view').classList.contains('rich-detail-active'), false);
  assert.equal(document.getElementById('stack-trace-breadcrumbs').querySelector('[data-action="back-to-list"]'), null);
  assert.equal(document.getElementById('stack-trace-view').classList.contains('rich-detail-active'), false);
  assert.equal(document.getElementById('stack-trace-grid').classList.contains('rich-detail-mode'), false);
  const codeView = document.getElementById('st-view-0');
  assert.ok(codeView, 'Expected classic #st-view-0 stack trace container');
  assert.match(codeView.querySelector('.occurrence-tag').textContent, /unitTest \(debug\)/);
  assert.match(codeView.querySelector('.stack-trace-pre').textContent, /ArithmeticException: \/ by zero/);
});

test('Screenshot single-suite report preserves classic matrix and delegates detail view to ScreenshotSuitePlugin', () => {
  const screenshotReport = {
    projectName: ':app',
    variants: ['debug'],
    testSuites: ['screenshotTest'],
    targets: ['default'],
    numberOfModules: 1,
    numberOfPackages: 1,
    numberOfClasses: 1,
    suiteDescriptors: {
      screenshotTest: {
        adapterId: 'screenshot',
        title: 'Screenshot Test',
        summaryColumns: [],
      },
    },
    modules: [
      {
        name: ':app',
        testSuiteSummaries: [summary('screenshotTest', 'debug', { total: 2, pass: 1, fail: 1, durationMs: 220 })],
        packages: [
          {
            name: 'com.example.ui',
            testSuiteSummaries: [summary('screenshotTest', 'debug', { total: 2, pass: 1, fail: 1, durationMs: 220 })],
            classes: [
              {
                name: 'CardScreenshotTest',
                testSuiteSummaries: [summary('screenshotTest', 'debug', { total: 2, pass: 1, fail: 1, durationMs: 220 })],
                testCases: [
                  {
                    name: 'cardDarkPreview',
                    targets: [
                      {
                        name: 'default',
                        testSuiteSummaries: [summary('screenshotTest', 'debug', { total: 1, fail: 1, durationMs: 120 })],
                        commonStackTraces: [
                          {
                            id: 'st_ss',
                            stackTrace: 'AssertionError: Image mismatch (2.4% pixels differ)',
                            occurrences: { screenshotTest: ['debug'] },
                          },
                        ],
                        testSuiteResults: [
                          {
                            testSuiteName: 'screenshotTest',
                            variantResults: {
                              debug: {
                                status: 'fail',
                                stackTraceId: 'st_ss',
                                adapterId: 'screenshot',
                                previewName: 'CardDarkPreview',
                                refImagePath: 'golden/card_dark.png',
                                newImagePath: 'actual/card_dark.png',
                                diffImagePath: 'diff/card_dark.png',
                              },
                            },
                          },
                        ],
                      },
                    ],
                  },
                  {
                    name: 'cardLightPreview',
                    targets: [
                      {
                        name: 'default',
                        testSuiteSummaries: [summary('screenshotTest', 'debug', { total: 1, pass: 1, durationMs: 100 })],
                        testSuiteResults: [
                          {
                            testSuiteName: 'screenshotTest',
                            variantResults: {
                              debug: {
                                status: 'pass',
                                adapterId: 'screenshot',
                                previewName: 'CardLightPreview',
                                refImagePath: 'golden/card_light.png',
                                newImagePath: 'actual/card_light.png',
                              },
                            },
                          },
                        ],
                      },
                    ],
                  },
                ],
              },
            ],
          },
        ],
      },
    ],
  };

  const { document, app } = createReportHarness(screenshotReport);

  assert.equal(app.usesRichSingleSuiteLayout(), false);
  assert.equal(document.getElementById('header-stats-multi').classList.contains('hidden'), false);
  assert.ok(document.getElementById('table-headers').querySelector('th[colspan="4"]'));

  // Both passing and failing screenshot tests are clickable via ScreenshotSuitePlugin
  const lightTrigger = document.getElementById('results-data').querySelector('[data-test-case="cardLightPreview"]');
  const darkTrigger = document.getElementById('results-data').querySelector('[data-test-case="cardDarkPreview"]');
  assert.ok(lightTrigger);
  assert.ok(darkTrigger);

  darkTrigger.click();
  assert.equal(app.state.detailViewMode, 'screenshot');
  assert.match(document.title, /^Stack Trace: cardDarkPreview - /);
  assert.equal(document.getElementById('copy-context-btn'), null);
  assert.equal(document.getElementById('stack-trace-view').classList.contains('rich-detail-active'), false);
  assert.equal(document.getElementById('stack-trace-breadcrumbs').querySelector('[data-action="back-to-list"]'), null);

  const layout = document.getElementById('stack-trace-grid').querySelector('.screenshot-layout');
  assert.ok(layout, 'Expected ScreenshotSuitePlugin .screenshot-layout');
  const sliderBtn = layout.querySelector('.mode-btn[data-mode="slider"]');
  assert.ok(sliderBtn);
  sliderBtn.click();
  assert.equal(layout.querySelector('.slider-view-wrapper').classList.contains('hidden'), false);
  assert.equal(layout.querySelector('.image-cards-grid').classList.contains('hidden'), true);

  // Sidebar switching updates main preview content
  const sidebarItems = layout.querySelectorAll('.sidebar-test-item');
  assert.equal(sidebarItems.length, 2);
  const lightSidebarItem = sidebarItems.find(el => el.dataset.testCase === 'cardLightPreview');
  lightSidebarItem.click();
  assert.match(layout.querySelector('.screenshot-main-content').textContent, /cardLightPreview/);
});

test('Backup single-suite report renders rich list, filters, lazy logs/rules, D2D neutral allowBackup, and Copy context', async () => {
  const backupReport = {
    projectName: ':app',
    variants: ['debug'],
    testSuites: ['backupTest'],
    targets: ['default'],
    numberOfModules: 1,
    numberOfPackages: 1,
    numberOfClasses: 1,
    suiteDescriptors: {
      backupTest: {
        adapterId: 'backupRestore',
        suiteType: 'androidx.test.backup',
        title: 'Backup & Restore',
        docsUrl: 'https://developer.android.com/guide/topics/data/autobackup',
        likelyCauseHeading: 'Backup rule that left the data out',
        summaryColumns: [
          { key: 'transport', label: 'Transport', filterable: true, tooltip: 'Backup transport mode' },
          { key: 'devices', label: 'Devices', filterable: true, tooltip: 'Source and target devices' },
        ],
      },
    },
    suiteEnvironments: [
      {
        envId: 'env_01',
        suiteName: 'backupTest',
        variantName: 'debug',
        targetName: 'default',
        modulePath: ':app',
        applicationId: 'com.example.journal',
        applicationName: 'Journal',
        versionName: '2.1.0',
        versionCode: 21,
        targetSdkVersion: 34,
        agpVersion: '9.6.0-dev',
        gradleVersion: '9.9.0',
        testLibraryVersion: '1.0.0-beta01',
        attributes: {
          allowBackup: 'false',
          fullBackupContent: '@xml/backup_rules',
          dataExtractionRules: '@xml/data_extraction_rules',
        },
        configFiles: [
          {
            key: 'fullBackupContent',
            label: 'backup_rules.xml',
            reference: '@xml/backup_rules',
            artifactPath: 'artifacts/env_01/backup_rules.xml',
          },
          {
            key: 'dataExtractionRules',
            label: 'data_extraction_rules.xml',
            reference: '@xml/data_extraction_rules',
            artifactPath: 'artifacts/env_01/data_extraction_rules.xml',
          },
        ],
      },
    ],
    modules: [
      {
        name: ':app',
        testSuiteSummaries: [summary('backupTest', 'debug', { total: 2, pass: 1, fail: 1, durationMs: 4200 })],
        packages: [
          {
            name: 'com.example.journal',
            testSuiteSummaries: [summary('backupTest', 'debug', { total: 2, pass: 1, fail: 1, durationMs: 4200 })],
            classes: [
              {
                name: 'JournalBackupTest',
                testSuiteSummaries: [summary('backupTest', 'debug', { total: 2, pass: 1, fail: 1, durationMs: 4200 })],
                testCases: [
                  {
                    name: 'cloudRestoreExcludedPrefFails',
                    targets: [
                      {
                        name: 'default',
                        testSuiteSummaries: [summary('backupTest', 'debug', { total: 1, fail: 1, durationMs: 2500 })],
                        commonStackTraces: [
                          {
                            id: 'st_br_1',
                            stackTrace: 'AssertionError: Expected session token in secret.xml after restore\n\tat com.example.journal.JournalBackupTest.cloudRestoreExcludedPrefFails(JournalBackupTest.kt:58)',
                            occurrences: { backupTest: ['debug'] },
                          },
                        ],
                        testSuiteResults: [
                          {
                            testSuiteName: 'backupTest',
                            variantResults: {
                              debug: {
                                status: 'fail',
                                durationMs: 2500,
                                stackTraceId: 'st_br_1',
                                detailId: 't_0001',
                                envId: 'env_01',
                                adapterId: 'backupRestore',
                                sourceLocation: {
                                  className: 'com.example.journal.JournalBackupTest',
                                  methodName: 'cloudRestoreExcludedPrefFails',
                                  file: 'JournalBackupTest.kt',
                                  line: 58,
                                },
                                summaryAttributes: {
                                  transport: { label: 'Transport', value: 'CLOUD', displayValue: 'Cloud' },
                                  devices: { label: 'Devices', value: 'Pixel 8 (API 34)', displayValue: 'Pixel 8 (API 34)' },
                                },
                                actors: [
                                  {
                                    id: 'source',
                                    role: 'SOURCE',
                                    name: 'Pixel 8',
                                    api: 34,
                                    image: 'google_apis',
                                    serial: 'emulator-5554',
                                    playServices: '26.33.30',
                                  },
                                ],
                                callout: {
                                  kind: 'FAILURE',
                                  failedStep: 2,
                                  title: 'Restore assertion failed',
                                  message: 'secret.xml was absent after restore',
                                  likelyCause: '<exclude domain="sharedpref" path="secret.xml"/> in backup_rules.xml excluded secret.xml.',
                                },
                                steps: [
                                  {
                                    shared: true,
                                    name: 'Seed app preferences',
                                    on: 'source',
                                    status: 'PASSED',
                                    durationMs: 400,
                                  },
                                  {
                                    n: 1,
                                    name: 'Trigger cloud backup',
                                    on: 'source',
                                    status: 'PASSED',
                                    durationMs: 1100,
                                  },
                                  {
                                    n: 2,
                                    name: 'Verify restored preferences',
                                    on: 'source',
                                    status: 'FAILED',
                                    durationMs: 1000,
                                  },
                                ],
                                logMeta: [
                                  { title: 'Host log', artifactPath: 'artifacts/t_0001/host-log.txt', lineCount: 2 },
                                  { title: 'Logcat (source)', artifactPath: 'artifacts/t_0001/logcat-emulator-5554.txt', lineCount: 2 },
                                ],
                                artifacts: [
                                  {
                                    name: 'Cloud backup archive',
                                    values: {
                                      Size: '2048 B',
                                      Entry: 'apps/com.example.journal/sp/public_prefs.xml (128 B)',
                                    },
                                  },
                                ],
                              },
                            },
                          },
                        ],
                      },
                    ],
                  },
                  {
                    name: 'd2dMigrationSucceeds',
                    targets: [
                      {
                        name: 'default',
                        testSuiteSummaries: [summary('backupTest', 'debug', { total: 1, pass: 1, durationMs: 1700 })],
                        testSuiteResults: [
                          {
                            testSuiteName: 'backupTest',
                            variantResults: {
                              debug: {
                                status: 'pass',
                                durationMs: 1700,
                                envId: 'env_01',
                                adapterId: 'backupRestore',
                                summaryAttributes: {
                                  transport: { label: 'Transport', value: 'DEVICE_TO_DEVICE', displayValue: 'D2D' },
                                  devices: { label: 'Devices', value: 'Pixel 8 -> Pixel 8a', displayValue: 'Pixel 8 -> Pixel 8a' },
                                },
                                actors: [
                                  { id: 'source', role: 'SOURCE', name: 'Pixel 8', api: 34, serial: 'emulator-5554' },
                                  { id: 'target', role: 'TARGET', name: 'Pixel 8a', api: 34, serial: 'emulator-5556' },
                                ],
                                steps: [
                                  { n: 1, name: 'Run D2D transfer', on: 'target', status: 'PASSED', durationMs: 1700 },
                                ],
                              },
                            },
                          },
                        ],
                      },
                    ],
                  },
                ],
              },
            ],
          },
        ],
      },
    ],
  };

  const lazyDetails = {
    t_0001: {
      logs: [
        { title: 'Host log', content: 'INFO Starting cloud backup\nSEVERE Assertion failed on secret.xml' },
        { title: 'Logcat (source)', content: '01-01 12:00:00.000 I/BackupManager: Backup complete\n01-01 12:00:01.000 E/BackupAgent: Excluded secret.xml' },
      ],
    },
    env_01: {
      files: [
        {
          key: 'fullBackupContent',
          label: 'backup_rules.xml',
          reference: '@xml/backup_rules',
          artifactPath: 'artifacts/env_01/backup_rules.xml',
          content: '<full-backup-content>\n  <include domain="sharedpref" path="."/>\n  <exclude domain="sharedpref" path="secret.xml"/>\n</full-backup-content>',
        },
        {
          key: 'dataExtractionRules',
          label: 'data_extraction_rules.xml',
          reference: '@xml/data_extraction_rules',
          artifactPath: 'artifacts/env_01/data_extraction_rules.xml',
          content: '<data-extraction-rules>\n  <device-transfer>\n    <include domain="sharedpref" path="."/>\n    <exclude domain="database" path="cache.db"/>\n  </device-transfer>\n</data-extraction-rules>',
        },
      ],
    },
  };

  const { document, app, SuiteRegistry, getClipboardText, flushAsync } = createReportHarness(backupReport, lazyDetails);

  // Rich single-suite header, env bar, status segments, and dynamic columns
  assert.equal(app.usesRichSingleSuiteLayout(), true);
  assert.equal(document.getElementById('header-stats-single').classList.contains('hidden'), false);
  assert.equal(document.getElementById('header-stats-multi').classList.contains('hidden'), true);
  const envBar = document.getElementById('suite-env-bar');
  assert.equal(envBar.classList.contains('hidden'), false);
  assert.match(envBar.textContent, /com\.example\.journal/);
  const suiteRulesBtn = envBar.querySelector('[data-action="toggle-suite-rules"]');
  assert.ok(suiteRulesBtn, 'Expected Backup rules disclosure button in suite environment bar');
  const suiteRulesDrawer = envBar.querySelector('[data-role="suite-rules-drawer"]');
  assert.ok(suiteRulesDrawer.classList.contains('hidden'));
  suiteRulesBtn.click();
  await flushAsync();
  assert.equal(suiteRulesDrawer.classList.contains('hidden'), false);
  assert.match(suiteRulesDrawer.textContent, /data_extraction_rules\.xml/);
  suiteRulesBtn.click();
  assert.equal(suiteRulesDrawer.classList.contains('hidden'), true);
  assert.equal(document.getElementById('status-segments').classList.contains('hidden'), false);

  const headerLabels = document
    .getElementById('table-headers')
    .querySelectorAll('th > span')
    .map(el => el.textContent.trim());
  assert.deepEqual(Array.from(headerLabels), ['Test', 'Transport', 'Devices', 'Status', 'Duration']);

  // Both failed and passing Backup tests are clickable; failed step is plain inline text (not a chip),
  // Transport renders both label and raw enum, and Devices renders API level in smaller muted .cell-sub
  const failRowTrigger = document.getElementById('results-data').querySelector('[data-test-case="cloudRestoreExcludedPrefFails"]');
  const passRowTrigger = document.getElementById('results-data').querySelector('[data-test-case="d2dMigrationSucceeds"]');
  assert.ok(failRowTrigger);
  assert.ok(passRowTrigger);
  assert.match(failRowTrigger.textContent, /failed at Verify restored preferences/);
  assert.equal(failRowTrigger.querySelector('.failed-step-badge'), null);
  assert.ok(failRowTrigger.querySelector('.failed-step-inline'));
  assert.match(passRowTrigger.textContent, /DEVICE_TO_DEVICE/);
  assert.ok(passRowTrigger.querySelector('.cell-sub'), 'Expected muted API level span in Devices cell');

  // Open failed test rich detail view and verify lazy log + backup rules rendering
  failRowTrigger.click();
  await flushAsync();

  assert.equal(app.state.detailViewMode, 'rich');
  assert.equal(document.getElementById('stack-trace-view').classList.contains('rich-detail-active'), true);
  assert.match(document.title, /^Test Details: cloudRestoreExcludedPrefFails - /);
  assert.ok(document.getElementById('copy-context-btn'));
  assert.ok(document.getElementById('stack-trace-breadcrumbs').querySelector('[data-action="back-to-list"]'));

  const grid = document.getElementById('stack-trace-grid');
  assert.equal(grid.classList.contains('rich-detail-mode'), true);
  assert.match(grid.textContent, /Failed at Verify restored preferences/);
  assert.match(grid.textContent, /Backup rule that left the data out/);
  assert.match(grid.textContent, /Prep/);
  assert.match(grid.textContent, /SHARED/);

  // Error line highlighted in lazy log viewer and only the single implicated rule file + line shown
  assert.ok(grid.querySelector('.detail-log-err'), 'Expected error line highlight in lazy log viewer');
  assert.equal(grid.querySelectorAll('.detail-rule-xml').length, 1, 'Expected only implicated backup_rules.xml on rule failure');
  const ruleHits = grid.querySelectorAll('.detail-rule-highlight');
  assert.equal(ruleHits.length, 1, 'Expected only the matching <exclude ... secret.xml/> line to be highlighted');
  assert.match(ruleHits[0].textContent, /secret\.xml/);
  assert.equal(grid.textContent.includes('cache.db'), false, 'Unused data_extraction_rules.xml must not repeat on backup_rules failure');
  // Cloud backup with allowBackup="false" gets fail badge tone
  assert.ok(grid.querySelector('.status-badge-lg.status-badge-sm.fail'), 'Expected allowBackup="false" fail badge on Cloud test');

  // Click Copy context and verify structured Markdown output
  document.getElementById('copy-context-btn').click();
  await flushAsync();
  const markdown = getClipboardText();
  assert.match(markdown, /## Test Report Context: cloudRestoreExcludedPrefFails/);
  assert.match(markdown, /\(Failed at Verify restored preferences\)/);
  assert.match(markdown, /`com\.example\.journal\.JournalBackupTest#cloudRestoreExcludedPrefFails`/);
  assert.match(markdown, /### Devices/);
  assert.match(markdown, /### Failure/);
  assert.match(markdown, /### Lifecycle/);
  assert.match(markdown, /### Backup Files & Rules/);
  assert.match(markdown, /### Log Excerpt \(Host log\)/);

  // Return to list and open passing D2D test: backup rules and redundant Devices(N) row are NOT repeated
  document.getElementById('stack-trace-breadcrumbs').querySelector('[data-action="back-to-list"]').click();
  assert.equal(app.state.currentView, 'report');

  const d2dTrigger = document.getElementById('results-data').querySelector('[data-test-case="d2dMigrationSucceeds"]');
  d2dTrigger.click();
  await flushAsync();
  const d2dGrid = document.getElementById('stack-trace-grid');
  assert.equal(d2dGrid.querySelector('.detail-rule-xml'), null, 'Passing tests must not repeat suite backup rules');
  assert.equal(d2dGrid.querySelector('.detail-actors-section'), null, 'Redundant Devices(N) row is omitted when Device fact column renders');
  assert.equal(d2dGrid.querySelector('.status-badge-lg.status-badge-sm.fail'), null);
  assert.match(d2dGrid.textContent, /Source\s*→\s*Target/);
  assert.match(d2dGrid.textContent, /emulator-5554 → emulator-5556/);
  assert.match(d2dGrid.textContent, /Lifecycle/);
  assert.ok(d2dGrid.querySelector('.detail-fact-value .cell-sub'));
  assert.ok(d2dGrid.querySelector('.detail-step-on.role-target'));
  assert.equal(d2dGrid.querySelector('.detail-step-num').textContent.trim(), '1');

  // When data_extraction_rules.xml covers both <cloud-backup> and <device-transfer> on API 31+,
  // legacy backup_rules.xml is omitted as obsolete.
  const backupPlugin = SuiteRegistry.resolve('backupRestore');
  const activeAndroid12 = backupPlugin.selectActiveRuleFiles(
    [
      {
        key: 'fullBackupContent',
        label: 'backup_rules.xml',
        content: '<full-backup-content><exclude domain="sharedpref" path="a.xml"/></full-backup-content>',
      },
      {
        key: 'dataExtractionRules',
        label: 'data_extraction_rules.xml',
        content: '<data-extraction-rules><cloud-backup><exclude domain="sharedpref" path="a.xml"/></cloud-backup><device-transfer/></data-extraction-rules>',
      },
    ],
    { targetSdkVersion: 34 }
  );
  assert.deepEqual(Array.from(activeAndroid12.map(f => f.label)), ['data_extraction_rules.xml']);
});

test('Multi-suite report keeps classic matrix in (all), activates rich layout on Backup selection, and isolates dual-suite classes', () => {
  const multiReport = {
    projectName: ':app',
    variants: ['debug'],
    testSuites: ['Aggregated', 'unitTest', 'screenshotTest', 'backupTest'],
    targets: ['default'],
    numberOfModules: 1,
    numberOfPackages: 1,
    numberOfClasses: 1,
    suiteDescriptors: {
      unitTest: { adapterId: 'default', title: 'Unit Test', summaryColumns: [] },
      screenshotTest: { adapterId: 'screenshot', title: 'Screenshot Test', summaryColumns: [] },
      backupTest: {
        adapterId: 'backupRestore',
        title: 'Backup & Restore',
        summaryColumns: [{ key: 'transport', label: 'Transport', filterable: true }],
      },
    },
    modules: [
      {
        name: ':app',
        testSuiteSummaries: [
          summary('Aggregated', 'debug', { total: 2, pass: 1, fail: 1, durationMs: 900 }),
          summary('unitTest', 'debug', { total: 1, fail: 1, durationMs: 50 }),
          summary('screenshotTest', 'debug', { total: 1, pass: 1, durationMs: 150 }),
          summary('backupTest', 'debug', { total: 1, pass: 1, durationMs: 700 }),
        ],
        packages: [
          {
            name: 'com.example',
            testSuiteSummaries: [
              summary('Aggregated', 'debug', { total: 2, pass: 1, fail: 1, durationMs: 900 }),
              summary('unitTest', 'debug', { total: 1, fail: 1, durationMs: 50 }),
              summary('screenshotTest', 'debug', { total: 1, pass: 1, durationMs: 150 }),
              summary('backupTest', 'debug', { total: 1, pass: 1, durationMs: 700 }),
            ],
            classes: [
              {
                name: 'SharedSuiteTest',
                testSuiteSummaries: [
                  summary('Aggregated', 'debug', { total: 2, pass: 1, fail: 1, durationMs: 900 }),
                  summary('unitTest', 'debug', { total: 1, fail: 1, durationMs: 50 }),
                  summary('screenshotTest', 'debug', { total: 1, pass: 1, durationMs: 150 }),
                  summary('backupTest', 'debug', { total: 1, pass: 1, durationMs: 700 }),
                ],
                testCases: [
                  {
                    name: 'dualSuiteCase',
                    targets: [
                      {
                        name: 'default',
                        testSuiteSummaries: [
                          summary('Aggregated', 'debug', { total: 1, fail: 1, durationMs: 200 }),
                          summary('unitTest', 'debug', { total: 1, fail: 1, durationMs: 50 }),
                          summary('screenshotTest', 'debug', { total: 1, pass: 1, durationMs: 150 }),
                        ],
                        commonStackTraces: [
                          {
                            id: 'st_unit',
                            stackTrace: 'AssertionError: Unit suite assertion failed',
                            occurrences: { unitTest: ['debug'] },
                          },
                        ],
                        testSuiteResults: [
                          {
                            testSuiteName: 'unitTest',
                            variantResults: {
                              debug: { status: 'fail', durationMs: 50, stackTraceId: 'st_unit', adapterId: 'default' },
                            },
                          },
                          {
                            testSuiteName: 'screenshotTest',
                            variantResults: {
                              debug: {
                                status: 'pass',
                                durationMs: 150,
                                adapterId: 'screenshot',
                                previewName: 'DualPreview',
                                refImagePath: 'golden/dual.png',
                              },
                            },
                          },
                        ],
                      },
                    ],
                  },
                  {
                    name: 'backupCase',
                    targets: [
                      {
                        name: 'default',
                        testSuiteSummaries: [
                          summary('Aggregated', 'debug', { total: 1, pass: 1, durationMs: 700 }),
                          summary('backupTest', 'debug', { total: 1, pass: 1, durationMs: 700 }),
                        ],
                        testSuiteResults: [
                          {
                            testSuiteName: 'backupTest',
                            variantResults: {
                              debug: {
                                status: 'pass',
                                durationMs: 700,
                                adapterId: 'backupRestore',
                                summaryAttributes: {
                                  transport: { label: 'Transport', value: 'CLOUD', displayValue: 'Cloud' },
                                },
                                steps: [{ n: 1, name: 'Backup', status: 'PASSED', durationMs: 700 }],
                              },
                            },
                          },
                        ],
                      },
                    ],
                  },
                ],
              },
            ],
          },
        ],
      },
    ],
  };

  const { document, app } = createReportHarness(multiReport);

  // Initial (all) view uses classic matrix
  assert.equal(app.state.filters.testSuite, 'all');
  assert.equal(app.usesRichSingleSuiteLayout(), false);
  assert.ok(document.getElementById('table-headers').querySelector('th[colspan="4"]'));

  // Switch to backupTest -> activates rich single-suite layout and allows sorting by duration
  app.state.filters.testSuite = 'backupTest';
  app.syncSortModeWithLayout();
  app.state.sort = { by: 'duration', order: 'desc' };
  app.render();
  assert.equal(app.usesRichSingleSuiteLayout(), true);
  assert.equal(document.getElementById('header-stats-single').classList.contains('hidden'), false);
  // The rich Backup list only shows test cases that ran in the Backup suite.
  assert.match(document.getElementById('results-data').textContent, /backupCase/);
  assert.doesNotMatch(document.getElementById('results-data').textContent, /dualSuiteCase/);

  // Switch back to (all) -> restores the classic sort that was active before the rich list
  app.state.filters.testSuite = 'all';
  app.syncSortModeWithLayout();
  app.render();
  assert.equal(app.usesRichSingleSuiteLayout(), false);
  assert.equal(app.state.sort.by, 'debug.fail');
  assert.ok(document.getElementById('table-headers').querySelector('th[colspan="4"]'));

  // A classic sort survives a visit to the rich Backup list unchanged.
  const selectSuite = name =>
    document.getElementById('testsuite-filter-list').querySelectorAll('button').find(b => b.textContent.trim() === name).click();
  app.state.sort = { by: 'debug.pass', order: 'asc' };
  app.render();
  selectSuite('backupTest');
  assert.equal(app.state.sort.by, 'status');
  selectSuite('all');
  assert.deepEqual({ ...app.state.sort }, { by: 'debug.pass', order: 'asc' });

  // "Back to tests" returns to the list even after the suite filter changed inside the rich detail view.
  selectSuite('backupTest');
  document.getElementById('results-data').querySelector('[data-test-case="backupCase"]').click();
  assert.equal(app.state.detailViewMode, 'rich');
  selectSuite('all');
  assert.equal(app.state.currentView, 'stack-trace');
  document.getElementById('stack-trace-breadcrumbs').querySelector('[data-action="back-to-list"]').click();
  assert.equal(app.state.currentView, 'report');
  assert.equal(app.state.filters.testSuite, 'backupTest');

  // Classic suites keep the existing behavior: a test case with screenshot results opens the screenshot view.
  app.state.filters.testSuite = 'unitTest';
  app.syncSortModeWithLayout();
  app.render();
  const unitTrigger = document.getElementById('results-data').querySelector('[data-test-case="dualSuiteCase"]');
  assert.ok(unitTrigger);
  unitTrigger.click();
  assert.equal(app.state.detailViewMode, 'screenshot');
  assert.ok(document.getElementById('stack-trace-grid').querySelector('.screenshot-layout'));
});

test('Custom suite with androidx.report.* entries uses generic DefaultSuitePlugin without Backup-specific labels', async () => {
  const customRichReport = {
    projectName: ':app',
    variants: ['debug'],
    testSuites: ['journeySuite'],
    targets: ['default'],
    numberOfModules: 1,
    numberOfPackages: 1,
    numberOfClasses: 1,
    suiteDescriptors: {
      journeySuite: {
        adapterId: 'default',
        title: 'Journey Suite',
        summaryColumns: [{ key: 'locale', label: 'Locale', filterable: true }],
      },
    },
    suiteEnvironments: [
      {
        envId: 'env_01',
        suiteName: 'journeySuite',
        variantName: 'debug',
        modulePath: ':app',
        applicationId: 'com.example.shop',
        configFiles: [
          {
            key: 'journeyConfig',
            label: 'journey_config.yaml',
            reference: 'src/journeySuite/journey_config.yaml',
            artifactPath: 'artifacts/env_01/journey_config.yaml',
          },
        ],
      },
    ],
    modules: [
      {
        name: ':app',
        testSuiteSummaries: [summary('journeySuite', 'debug', { total: 2, pass: 2, durationMs: 760 })],
        packages: [
          {
            name: 'com.example.shop',
            testSuiteSummaries: [summary('journeySuite', 'debug', { total: 2, pass: 2, durationMs: 760 })],
            classes: [
              {
                name: 'CheckoutJourneyTest',
                testSuiteSummaries: [summary('journeySuite', 'debug', { total: 2, pass: 2, durationMs: 760 })],
                testCases: [
                  {
                    name: 'completesCheckoutFlow',
                    targets: [
                      {
                        name: 'default',
                        testSuiteSummaries: [summary('journeySuite', 'debug', { total: 1, pass: 1, durationMs: 640 })],
                        testSuiteResults: [
                          {
                            testSuiteName: 'journeySuite',
                            variantResults: {
                              debug: {
                                status: 'pass',
                                durationMs: 640,
                                envId: 'env_01',
                                adapterId: 'default',
                                summaryAttributes: {
                                  locale: { label: 'Locale', value: 'en-US', displayValue: 'en-US' },
                                },
                                steps: [
                                  { n: 1, name: 'Open cart', status: 'PASSED', durationMs: 300 },
                                  { n: 2, name: 'Confirm order', status: 'PASSED', durationMs: 340 },
                                ],
                              },
                            },
                          },
                        ],
                      },
                    ],
                  },
                  {
                    name: 'plainSanityCheckInRichSuite',
                    targets: [
                      {
                        name: 'default',
                        testSuiteSummaries: [summary('journeySuite', 'debug', { total: 1, pass: 1, durationMs: 120 })],
                        testSuiteResults: [
                          {
                            testSuiteName: 'journeySuite',
                            variantResults: {
                              debug: {
                                status: 'pass',
                                durationMs: 120,
                                envId: 'env_01',
                                adapterId: 'default',
                              },
                            },
                          },
                        ],
                      },
                    ],
                  },
                ],
              },
            ],
          },
        ],
      },
    ],
  };

  const { document, app, flushAsync } = createReportHarness(customRichReport, {
    env_01: {
      files: [
        {
          key: 'journeyConfig',
          label: 'journey_config.yaml',
          reference: 'src/journeySuite/journey_config.yaml',
          artifactPath: 'artifacts/env_01/journey_config.yaml',
          content: 'locale: en-US\nretries: 1',
        },
      ],
    },
  });

  assert.equal(app.usesRichSingleSuiteLayout(), true);
  const headerLabels = document
    .getElementById('table-headers')
    .querySelectorAll('th > span')
    .map(el => el.textContent.trim());
  assert.deepEqual(Array.from(headerLabels), ['Test', 'Locale', 'Status', 'Duration']);

  const trigger = document.getElementById('results-data').querySelector('[data-test-case="completesCheckoutFlow"]');
  trigger.click();
  await flushAsync();

  const gridText = document.getElementById('stack-trace-grid').textContent;
  assert.match(gridText, /Configuration/);
  assert.match(gridText, /journey_config\.yaml/);
  assert.doesNotMatch(gridText, /Backup rules/);

  // Passing entry-less row in the same rich custom suite opens RichTestDetailView instead of empty classic stack trace.
  app.showReportView();
  const entrylessTrigger = document.getElementById('results-data').querySelector('[data-test-case="plainSanityCheckInRichSuite"]');
  assert.ok(entrylessTrigger, 'Expected passing row in rich custom suite to be clickable');
  entrylessTrigger.click();
  await flushAsync();
  assert.equal(app.state.detailViewMode, 'rich');
  assert.ok(document.getElementById('stack-trace-breadcrumbs').querySelector('[data-action="back-to-list"]'));
  assert.doesNotMatch(document.getElementById('stack-trace-grid').textContent, /No stack trace available/);
  assert.match(document.title, /^Test Details: plainSanityCheckInRichSuite/);
});

test('Plain custom suite without androidx.report.* entries preserves classic header and 4-column matrix', () => {
  const plainCustomSuiteReport = {
    projectName: ':app',
    variants: ['debug'],
    testSuites: ['smokeSuite'],
    targets: ['default'],
    numberOfModules: 1,
    numberOfPackages: 1,
    numberOfClasses: 1,
    suiteDescriptors: {
      smokeSuite: {
        adapterId: 'default',
        title: 'Smoke Suite',
        summaryColumns: [],
      },
    },
    suiteEnvironments: [
      {
        envId: 'env_01',
        suiteName: 'smokeSuite',
        variantName: 'debug',
        modulePath: ':app',
        applicationId: 'com.example.smoke',
      },
    ],
    modules: [
      {
        name: ':app',
        testSuiteSummaries: [summary('smokeSuite', 'debug', { total: 1, pass: 1, durationMs: 80 })],
        packages: [
          {
            name: 'com.example.smoke',
            testSuiteSummaries: [summary('smokeSuite', 'debug', { total: 1, pass: 1, durationMs: 80 })],
            classes: [
              {
                name: 'SmokeCheckTest',
                testSuiteSummaries: [summary('smokeSuite', 'debug', { total: 1, pass: 1, durationMs: 80 })],
                testCases: [
                  {
                    name: 'appLaunchesCleanly',
                    targets: [
                      {
                        name: 'default',
                        testSuiteSummaries: [summary('smokeSuite', 'debug', { total: 1, pass: 1, durationMs: 80 })],
                        testSuiteResults: [
                          {
                            testSuiteName: 'smokeSuite',
                            variantResults: {
                              debug: {
                                status: 'pass',
                                durationMs: 80,
                                envId: 'env_01',
                                adapterId: 'default',
                              },
                            },
                          },
                        ],
                      },
                    ],
                  },
                ],
              },
            ],
          },
        ],
      },
    ],
  };

  const { document, app } = createReportHarness(plainCustomSuiteReport);
  assert.equal(app.usesRichSingleSuiteLayout(), false);
  assert.equal(document.getElementById('header-stats-multi').classList.contains('hidden'), false);
  assert.equal(document.getElementById('header-stats-single').classList.contains('hidden'), true);
  assert.equal(document.getElementById('suite-env-bar').classList.contains('hidden'), true);
  assert.ok(document.getElementById('table-headers').querySelector('th[colspan="4"]'));
  assert.equal(document.getElementById('results-data').querySelector('[data-test-case="appLaunchesCleanly"]'), null);
});

test('partial lazy rule files preserve remaining configFiles, trailing log newlines match lineCount, and markdownFence sanitizes lang', async () => {
  const partialBackupReport = {
    projectName: ':app',
    variants: ['debug'],
    testSuites: ['backupTest'],
    targets: ['default'],
    numberOfModules: 1,
    numberOfPackages: 1,
    numberOfClasses: 1,
    suiteDescriptors: {
      backupTest: {
        adapterId: 'backupRestore',
        suiteType: 'androidx.test.backup',
        title: 'Backup & Restore',
        summaryColumns: [],
      },
    },
    suiteEnvironments: [
      {
        envId: 'env_01',
        suiteName: 'backupTest',
        variantName: 'debug',
        modulePath: ':app',
        applicationId: 'com.example.partial',
        attributes: { allowBackup: 'true' },
        configFiles: [
          {
            key: 'fullBackupContent',
            label: 'fullBackupContent',
            reference: 'true',
          },
          {
            key: 'dataExtractionRules',
            label: 'data_extraction_rules.xml',
            reference: '@xml/data_extraction_rules',
            artifactPath: 'artifacts/env_01/data_extraction_rules.xml',
          },
        ],
      },
    ],
    modules: [
      {
        name: ':app',
        testSuiteSummaries: [summary('backupTest', 'debug', { total: 2, pass: 1, fail: 1, durationMs: 500 })],
        packages: [
          {
            name: 'com.example.partial',
            testSuiteSummaries: [summary('backupTest', 'debug', { total: 2, pass: 1, fail: 1, durationMs: 500 })],
            classes: [
              {
                name: 'PartialConfigBackupTest',
                testSuiteSummaries: [summary('backupTest', 'debug', { total: 2, pass: 1, fail: 1, durationMs: 500 })],
                testCases: [
                  {
                    name: 'backsUpData',
                    targets: [
                      {
                        name: 'default',
                        testSuiteSummaries: [summary('backupTest', 'debug', { total: 1, pass: 1, durationMs: 250 })],
                        testSuiteResults: [
                          {
                            testSuiteName: 'backupTest',
                            variantResults: {
                              debug: {
                                status: 'pass',
                                durationMs: 250,
                                detailId: 't_0001',
                                envId: 'env_01',
                                adapterId: 'backupRestore',
                                steps: [{ n: 1, name: 'Run backup', on: 'source', status: 'PASSED', durationMs: 250 }],
                              },
                            },
                          },
                        ],
                      },
                    ],
                  },
                  {
                    name: 'ruleExcludedFileFails',
                    targets: [
                      {
                        name: 'default',
                        testSuiteSummaries: [summary('backupTest', 'debug', { total: 1, fail: 1, durationMs: 250 })],
                        testSuiteResults: [
                          {
                            testSuiteName: 'backupTest',
                            variantResults: {
                              debug: {
                                status: 'fail',
                                durationMs: 250,
                                failedStep: 1,
                                envId: 'env_01',
                                adapterId: 'backupRestore',
                                callout: {
                                  title: 'Expected file missing after restore',
                                  message: 'prefs.xml was not restored',
                                  likelyCause: 'Backup rule excluded shared preferences',
                                },
                                steps: [{ n: 1, name: 'Verify', on: 'source', status: 'FAILED', durationMs: 250 }],
                              },
                            },
                          },
                        ],
                      },
                    ],
                  },
                ],
              },
            ],
          },
        ],
      },
    ],
  };

  const { SuiteRegistry, document, getClipboardText, flushAsync } = createReportHarness(partialBackupReport, {
    env_01: {
      files: [
        {
          key: 'dataExtractionRules',
          label: 'data_extraction_rules.xml',
          reference: '@xml/data_extraction_rules',
          artifactPath: 'artifacts/env_01/data_extraction_rules.xml',
          content: '<data-extraction-rules/>',
        },
      ],
    },
    t_0001: {
      systemOut: 'line one\nline two\n',
    },
  });

  // Suite-level disclosure preserves fallback fullBackupContent when data_extraction_rules.xml omits <cloud-backup>
  const suiteRulesBtn = document.getElementById('suite-env-bar').querySelector('[data-action="toggle-suite-rules"]');
  assert.ok(suiteRulesBtn);
  suiteRulesBtn.click();
  await flushAsync();
  const drawerText = document.getElementById('suite-env-bar').querySelector('[data-role="suite-rules-drawer"]').textContent;
  assert.match(drawerText, /data_extraction_rules\.xml/);
  assert.match(drawerText, /android:fullBackupContent/);

  // Passing test omits repeated backup rules on detail view while preserving log line counting
  document.getElementById('results-data').querySelector('[data-test-case="backsUpData"]').click();
  await flushAsync();

  const passDetailText = document.getElementById('stack-trace-grid').textContent;
  assert.doesNotMatch(passDetailText, /data_extraction_rules\.xml/);

  const logMetaEl = document.getElementById('stack-trace-grid').querySelector('[data-role="log-stream-meta"]');
  assert.equal(logMetaEl.textContent.trim(), '2 lines');
  const logPre = document.getElementById('stack-trace-grid').querySelector('pre.detail-log');
  assert.equal(logPre.textContent, 'line one\nline two');

  // Rule-implicated failure renders partial lazy rule file + remaining fallback configFiles
  document.getElementById('stack-trace-breadcrumbs').querySelector('[data-action="back-to-list"]').click();
  document.getElementById('results-data').querySelector('[data-test-case="ruleExcludedFileFails"]').click();
  await flushAsync();

  const failDetailText = document.getElementById('stack-trace-grid').textContent;
  assert.match(failDetailText, /data_extraction_rules\.xml/);
  assert.match(failDetailText, /android:fullBackupContent/);

  document.getElementById('copy-context-btn').click();
  await flushAsync();
  const copied = getClipboardText();
  assert.match(copied, /data_extraction_rules\.xml/);
  assert.match(copied, /android:fullBackupContent.*`true`/);

  const sanitizedFence = SuiteRegistry.markdownFence('alpha', 'xml`\ninjected');
  assert.equal(sanitizedFence.open, '```xmlinjected');
  assert.equal(sanitizedFence.close, '```');
});

test('attribute filter clicks, variant-filtered screenshots, and detail-view filter switches stay synchronized', () => {
  const nodeSummaries = [
    {
      name: 'Aggregated',
      variantSummaries: [
        summary('Aggregated', 'debug', { total: 1, pass: 1, durationMs: 200 }).variantSummaries[0],
        summary('Aggregated', 'release', { total: 2, pass: 1, fail: 1, durationMs: 400 }).variantSummaries[0],
      ],
    },
    summary('screenshotTest', 'debug', { total: 1, pass: 1, durationMs: 200 }),
    summary('unitTest', 'release', { total: 1, fail: 1, durationMs: 150 }),
    summary('backupTest', 'release', { total: 2, pass: 2, durationMs: 250 }),
  ];

  const specialAttrReport = {
    projectName: 'FilterSyncProject',
    variants: ['debug', 'release'],
    testSuites: ['Aggregated', 'screenshotTest', 'unitTest', 'backupTest'],
    targets: ['default'],
    suiteDescriptors: {
      screenshotTest: { adapterId: 'screenshot', summaryColumns: [] },
      unitTest: { adapterId: 'default', summaryColumns: [] },
      backupTest: {
        adapterId: 'backupRestore',
        suiteType: 'androidx.test.backup',
        summaryColumns: [
          { key: 'device"id', label: 'Device ID', filterable: true, sortable: true },
        ],
      },
    },
    suiteEnvironments: [],
    modules: [
      {
        name: ':app',
        testSuiteSummaries: nodeSummaries,
        packages: [
          {
            name: 'com.example.sync',
            testSuiteSummaries: nodeSummaries,
            classes: [
              {
                name: 'com.example.sync.MixedVariantTest',
                testSuiteSummaries: nodeSummaries,
                testCases: [
                  {
                    name: 'verifyCrossVariantState',
                    targets: [
                      {
                        name: 'default',
                        commonStackTraces: [
                          {
                            id: 'st_rel',
                            occurrences: { unitTest: ['release'] },
                            stackTrace: 'java.lang.AssertionError: release unitTest failed',
                          },
                        ],
                        testSuiteResults: [
                          {
                            testSuiteName: 'Aggregated',
                            variantResults: {
                              debug: { status: 'pass', durationMs: 200 },
                              release: { status: 'fail', durationMs: 300, stackTraceId: 'st_rel' },
                            },
                          },
                          {
                            testSuiteName: 'screenshotTest',
                            variantResults: {
                              debug: {
                                status: 'pass',
                                adapterId: 'screenshot',
                                durationMs: 200,
                                previewName: 'DebugCardPreview',
                                refImagePath: 'ref/debug.png',
                                newImagePath: 'new/debug.png',
                              },
                            },
                          },
                          {
                            testSuiteName: 'unitTest',
                            variantResults: {
                              release: {
                                status: 'fail',
                                adapterId: 'default',
                                durationMs: 150,
                                stackTraceId: 'st_rel',
                              },
                            },
                          },
                          {
                            testSuiteName: 'backupTest',
                            variantResults: {
                              release: {
                                status: 'pass',
                                adapterId: 'backupRestore',
                                suiteType: 'androidx.test.backup',
                                durationMs: 150,
                                summaryAttributes: {
                                  'device"id': { value: 'emu"5554', displayValue: 'Pixel "8"', label: 'Device ID' },
                                },
                                steps: [{ n: 1, name: 'Backup app', status: 'PASSED', durationMs: 150 }],
                              },
                            },
                          },
                        ],
                      },
                    ],
                  },
                  {
                    name: 'otherDeviceBackupOnly',
                    targets: [
                      {
                        name: 'default',
                        commonStackTraces: [],
                        testSuiteResults: [
                          {
                            testSuiteName: 'Aggregated',
                            variantResults: {
                              release: { status: 'pass', durationMs: 100 },
                            },
                          },
                          {
                            testSuiteName: 'backupTest',
                            variantResults: {
                              release: {
                                status: 'pass',
                                adapterId: 'backupRestore',
                                suiteType: 'androidx.test.backup',
                                durationMs: 100,
                                summaryAttributes: {
                                  'device"id': { value: 'emu"5556', displayValue: 'Pixel "9"', label: 'Device ID' },
                                },
                                steps: [{ n: 1, name: 'Backup secondary', status: 'PASSED', durationMs: 100 }],
                              },
                            },
                          },
                        ],
                      },
                    ],
                  },
                ],
              },
            ],
          },
        ],
      },
    ],
  };

  const { app, document, SuiteRegistry } = createReportHarness(specialAttrReport);

  // 1. Clicking dynamic suite attribute option works without global CSS.escape, filters #results-data rows, and focuses the re-rendered button.
  app.state.filters.testSuite = 'backupTest';
  app.state.filters.variants = ['release'];
  app.state.viewLevel = 'testCases';
  app.syncSortModeWithLayout();
  app.updateTestSuiteUI();
  app.render();

  const beforeFilterText = document.getElementById('results-data').textContent;
  assert.match(beforeFilterText, /verifyCrossVariantState/);
  assert.match(beforeFilterText, /otherDeviceBackupOnly/);

  const optionItem = document
    .getElementById('suite-attr-filters')
    .querySelectorAll('button.popover-item')
    .find(el => el.dataset.attrValue === 'emu"5554');
  assert.ok(optionItem, 'Expected dynamic attribute option button');
  optionItem.click();
  assert.equal(app.state.filters.suiteAttributes['device"id'], 'emu"5554');
  assert.equal(document.activeElement.dataset.attrFilterBtn, 'device"id');
  const afterFilterText = document.getElementById('results-data').textContent;
  assert.match(afterFilterText, /verifyCrossVariantState/);
  assert.match(afterFilterText, /Pixel "8"/);
  assert.doesNotMatch(afterFilterText, /otherDeviceBackupOnly/);

  // 2. Classic suites keep the existing behavior: screenshot results open the screenshot view for any variant filter.
  app.state.filters.suiteAttributes = {};
  app.state.filters.testSuite = 'unitTest';
  app.state.filters.variants = ['release'];
  app.syncSortModeWithLayout();
  app.updateTestSuiteUI();
  app.render();

  const tcNode = app.processedData.modules[0].packages[0].classes[0].testCases[0];
  app.state.filters.testSuite = 'all';
  assert.equal(SuiteRegistry.findCustomDetailMatch(tcNode, app).plugin.id, 'screenshot');

  app.showStackTraceView(tcNode, {
    moduleName: ':app',
    packageName: 'com.example.sync',
    className: 'com.example.sync.MixedVariantTest',
    testCaseName: 'verifyCrossVariantState',
  });
  assert.equal(app.state.detailViewMode, 'screenshot');
  assert.match(document.getElementById('stack-trace-grid').textContent, /DebugCardPreview/);
  assert.equal(document.getElementById('stack-trace-breadcrumbs').querySelector('[data-action="back-to-list"]'), null);
  assert.match(document.title, /^Stack Trace: verifyCrossVariantState/);

  // 3. Switching testSuite to backupTest while inside the detail view switches to the rich view.
  app.state.filters.testSuite = 'backupTest';
  app.updateTestSuiteUI();
  app.render();
  assert.equal(app.state.detailViewMode, 'rich');
  assert.ok(document.getElementById('stack-trace-breadcrumbs').querySelector('[data-action="back-to-list"]'));
  assert.match(document.title, /^Test Details: verifyCrossVariantState/);
  const announcer = document.getElementById('a11y-announcer');
  if (announcer) {
    assert.doesNotMatch(announcer.textContent, /Showing \d+ results\./);
  }

  // 4. The screenshot sidebar lists every test case of the class with its overall status, as before.
  app.state.filters.testSuite = 'screenshotTest';
  app.state.filters.variants = ['debug'];
  app.updateTestSuiteUI();
  app.showStackTraceView(tcNode, {
    moduleName: ':app',
    packageName: 'com.example.sync',
    className: 'com.example.sync.MixedVariantTest',
    testCaseName: 'verifyCrossVariantState',
  });
  assert.equal(app.state.detailViewMode, 'screenshot');
  const sidebarItems = document
    .getElementById('stack-trace-grid')
    .querySelectorAll('.screenshot-sidebar .sidebar-test-item');
  assert.deepEqual(
    sidebarItems.map(el => el.dataset.testCase),
    ['verifyCrossVariantState', 'otherDeviceBackupOnly']
  );
  assert.ok(sidebarItems[0].querySelector('.tc-status-icon.fail'), 'Expected the overall fail icon across suites and variants');
});

test('multi-target reports keep the classic target column for classic suites and collapse it for rich suites', () => {
  const multiTargetAndAttachmentReport = {
    projectName: ':app',
    variants: ['debug'],
    testSuites: ['UnitTest', 'androidTest', 'screenshotTest', 'backupTest'],
    targets: ['connected', 'default', 'host'],
    numberOfModules: 1,
    numberOfPackages: 1,
    numberOfClasses: 1,
    suiteDescriptors: {
      screenshotTest: { adapterId: 'screenshot', title: 'Screenshot Tests' },
      backupTest: {
        adapterId: 'backupRestore',
        title: 'Backup & Restore',
        summaryColumns: [{ key: 'transport', label: 'Transport', filterable: true }],
      },
    },
    suiteEnvironments: [],
    modules: [
      {
        name: ':app',
        testSuiteSummaries: [
          summary('Aggregated', 'debug', { total: 2, pass: 2, durationMs: 500 }),
          summary('UnitTest', 'debug', { total: 2, pass: 2, durationMs: 120 }),
          summary('androidTest', 'debug', { total: 1, pass: 1, durationMs: 180 }),
          summary('screenshotTest', 'debug', { total: 1, pass: 1, durationMs: 90 }),
          summary('backupTest', 'debug', { total: 1, pass: 1, durationMs: 210 }),
        ],
        packages: [
          {
            name: 'com.example.multi',
            testSuiteSummaries: [
              summary('Aggregated', 'debug', { total: 2, pass: 2, durationMs: 500 }),
              summary('UnitTest', 'debug', { total: 2, pass: 2, durationMs: 120 }),
              summary('androidTest', 'debug', { total: 1, pass: 1, durationMs: 180 }),
              summary('screenshotTest', 'debug', { total: 1, pass: 1, durationMs: 90 }),
              summary('backupTest', 'debug', { total: 1, pass: 1, durationMs: 210 }),
            ],
            classes: [
              {
                name: 'CrossTargetSuiteTest',
                testSuiteSummaries: [
                  summary('Aggregated', 'debug', { total: 2, pass: 2, durationMs: 500 }),
                  summary('UnitTest', 'debug', { total: 2, pass: 2, durationMs: 120 }),
                  summary('androidTest', 'debug', { total: 1, pass: 1, durationMs: 180 }),
                  summary('screenshotTest', 'debug', { total: 1, pass: 1, durationMs: 90 }),
                  summary('backupTest', 'debug', { total: 1, pass: 1, durationMs: 210 }),
                ],
                testCases: [
                  {
                    name: 'sharedAcrossConnectedAndDefault',
                    // Alphabetically sorted targets: 'connected' precedes 'default' and 'host'.
                    targets: [
                      {
                        name: 'connected',
                        testSuiteSummaries: [
                          summary('Aggregated', 'debug', { total: 1, pass: 1, durationMs: 180 }),
                          summary('androidTest', 'debug', { total: 1, pass: 1, durationMs: 180 }),
                        ],
                        testSuiteResults: [
                          {
                            testSuiteName: 'androidTest',
                            variantResults: {
                              debug: {
                                status: 'pass',
                                adapterId: 'default',
                                durationMs: 180,
                              },
                            },
                          },
                        ],
                      },
                      {
                        name: 'default',
                        testSuiteSummaries: [
                          summary('Aggregated', 'debug', { total: 1, pass: 1, durationMs: 300 }),
                          summary('screenshotTest', 'debug', { total: 1, pass: 1, durationMs: 90 }),
                          summary('backupTest', 'debug', { total: 1, pass: 1, durationMs: 210 }),
                        ],
                        testSuiteResults: [
                          {
                            testSuiteName: 'screenshotTest',
                            variantResults: {
                              debug: {
                                status: 'pass',
                                adapterId: 'screenshot',
                                durationMs: 90,
                                previewName: 'DefaultTargetPreview',
                                refImagePath: 'ref/card.png',
                                newImagePath: 'new/card.png',
                              },
                            },
                          },
                          {
                            testSuiteName: 'backupTest',
                            variantResults: {
                              debug: {
                                status: 'pass',
                                adapterId: 'backupRestore',
                                suiteType: 'androidx.test.backup',
                                durationMs: 210,
                                summaryAttributes: {
                                  transport: { value: 'CLOUD', displayValue: 'Cloud', label: 'Transport' },
                                },
                                steps: [{ n: 1, name: 'Backup data', status: 'PASSED', durationMs: 210 }],
                              },
                            },
                          },
                        ],
                      },
                      {
                        name: 'host',
                        testSuiteSummaries: [
                          summary('Aggregated', 'debug', { total: 1, pass: 1, durationMs: 70 }),
                          summary('UnitTest', 'debug', { total: 1, pass: 1, durationMs: 70 }),
                        ],
                        testSuiteResults: [
                          {
                            testSuiteName: 'UnitTest',
                            variantResults: {
                              debug: {
                                status: 'pass',
                                adapterId: 'default',
                                durationMs: 70,
                              },
                            },
                          },
                        ],
                      },
                    ],
                  },
                  {
                    name: 'plainPassingUnitTest',
                    targets: [
                      {
                        name: 'host',
                        testSuiteSummaries: [
                          summary('Aggregated', 'debug', { total: 1, pass: 1, durationMs: 50 }),
                          summary('UnitTest', 'debug', { total: 1, pass: 1, durationMs: 50 }),
                        ],
                        testSuiteResults: [
                          {
                            testSuiteName: 'UnitTest',
                            variantResults: {
                              debug: { status: 'pass', adapterId: 'default', durationMs: 50 },
                            },
                          },
                        ],
                      },
                    ],
                  },
                ],
              },
            ],
          },
        ],
      },
    ],
  };

  const { app, document } = createReportHarness(multiTargetAndAttachmentReport);
  app.state.viewMode = 'flat';
  app.state.currentFlatView = 'testCases';

  // 1. Classic suites keep the classic layout and the Target column of the report's targets.
  for (const classicSuite of ['UnitTest', 'androidTest', 'screenshotTest']) {
    app.state.filters.testSuite = classicSuite;
    app.syncSortModeWithLayout();
    app.updateTestSuiteUI();
    app.render();
    assert.equal(app.usesRichSingleSuiteLayout(), false, `${classicSuite} must keep classic layout`);
    assert.equal(app.hasOnlyDefaultTarget(), false, `${classicSuite} must keep the Target column`);
    assert.ok(document.getElementById('table-headers').querySelector('th.col-target'));
    assert.ok(document.getElementById('table-headers').querySelector('th[colspan="4"]'));
  }

  // Passing classic tests without screenshots or failures stay non-clickable.
  app.state.filters.testSuite = 'UnitTest';
  app.syncSortModeWithLayout();
  app.updateTestSuiteUI();
  app.render();
  assert.equal(
    document.getElementById('results-data').querySelector('[data-test-case="plainPassingUnitTest"]'),
    null
  );
  const unitTriggers = document
    .getElementById('results-data')
    .querySelectorAll('[data-test-case="sharedAcrossConnectedAndDefault"]');
  assert.deepEqual(unitTriggers.map(el => el.dataset.target), ['default']);

  // 2. The rich Backup layout counts only its own 'default' target, so the Target column collapses.
  app.state.filters.testSuite = 'backupTest';
  app.syncSortModeWithLayout();
  app.updateTestSuiteUI();
  app.render();
  assert.equal(app.hasOnlyDefaultTarget(), true);
  assert.equal(app.usesRichSingleSuiteLayout(), true);
  assert.equal(document.getElementById('table-headers').querySelector('th.col-target'), null);
  assert.match(document.getElementById('results-data').textContent, /sharedAcrossConnectedAndDefault/);
  assert.match(document.getElementById('results-data').textContent, /Cloud/);
  assert.equal(app.computeActiveSummaryStats().passed, 1);

  // 3. screenshotTest keeps one row per target; its 'default' row opens the screenshot view.
  app.state.filters.testSuite = 'screenshotTest';
  app.syncSortModeWithLayout();
  app.updateTestSuiteUI();
  app.render();
  const screenshotTrigger = document
    .getElementById('results-data')
    .querySelector('[data-test-case="sharedAcrossConnectedAndDefault"][data-target="default"]');
  assert.ok(screenshotTrigger, 'screenshotTest default-target row must be clickable');
  screenshotTrigger.click();
  assert.equal(app.state.detailViewMode, 'screenshot');
  assert.match(document.getElementById('stack-trace-grid').textContent, /DefaultTargetPreview/);
});

test('classic layouts keep the existing search, suite filter, clickability and status label behavior', () => {
  const tc = (name, suiteName, target, result, stackTraces = []) => ({
    name,
    targets: [
      {
        name: target,
        testSuiteSummaries: [
          summary('Aggregated', 'debug', { total: 1, pass: result.status === 'pass' ? 1 : 0, fail: result.status === 'fail' ? 1 : 0 }),
          summary(suiteName, 'debug', { total: 1, pass: result.status === 'pass' ? 1 : 0, fail: result.status === 'fail' ? 1 : 0 }),
        ],
        commonStackTraces: stackTraces,
        testSuiteResults: [{ testSuiteName: suiteName, variantResults: { debug: result } }],
      },
    ],
  });
  const classSummaries = [
    summary('Aggregated', 'debug', { total: 3, pass: 1, fail: 2 }),
    summary('unitTest', 'debug', { total: 2, pass: 1, fail: 1 }),
    summary('smokeSuite', 'debug', { total: 1, fail: 1 }),
  ];
  const report = {
    projectName: ':app',
    variants: ['debug'],
    testSuites: ['Aggregated', 'unitTest', 'smokeSuite'],
    targets: ['host', 'smoke'],
    numberOfModules: 1,
    numberOfPackages: 1,
    numberOfClasses: 1,
    suiteDescriptors: {},
    suiteEnvironments: [],
    modules: [
      {
        name: ':app',
        testSuiteSummaries: classSummaries,
        packages: [
          {
            name: 'com.example.parity',
            testSuiteSummaries: classSummaries,
            classes: [
              {
                name: 'ParityTest',
                testSuiteSummaries: classSummaries,
                testCases: [
                  tc('unitPasses', 'unitTest', 'host', {
                    status: 'pass',
                    adapterId: 'default',
                    properties: { owner: 'needle-property' },
                  }),
                  tc('unitFailsWithTrace', 'unitTest', 'host', { status: 'fail', adapterId: 'default', stackTraceId: 'st_1' }, [
                    { id: 'st_1', stackTrace: 'java.lang.AssertionError: needle-stack', occurrences: { unitTest: ['debug'] } },
                  ]),
                  tc('smokeFailsWithoutTrace', 'smokeSuite', 'smoke', { status: 'fail', adapterId: 'default' }),
                ],
              },
            ],
          },
        ],
      },
    ],
  };

  const { app, document } = createReportHarness(report);
  app.state.viewMode = 'flat';
  app.state.currentFlatView = 'testCases';
  const rows = () => document.getElementById('results-data').textContent;

  // Search matches names only, not stack traces or properties.
  app.state.filters.search = 'needle';
  app.render();
  assert.doesNotMatch(rows(), /unitPasses|unitFailsWithTrace/);
  app.state.filters.search = 'ParityTest';
  app.render();
  assert.match(rows(), /unitPasses/);
  app.state.filters.search = '';

  // Selecting a suite filters classes, not the test cases inside a matching class.
  app.state.filters.testSuite = 'unitTest';
  app.updateTestSuiteUI();
  app.render();
  assert.match(rows(), /smokeFailsWithoutTrace/);

  // Only failures with a stack trace are clickable.
  app.state.filters.testSuite = 'all';
  app.updateTestSuiteUI();
  app.render();
  const results = document.getElementById('results-data');
  assert.ok(results.querySelector('[data-test-case="unitFailsWithTrace"]'));
  assert.equal(results.querySelector('[data-test-case="smokeFailsWithoutTrace"]'), null);
  assert.equal(results.querySelector('[data-test-case="unitPasses"]'), null);

  // Clearing every status keeps the "Status: All" label.
  app.state.filters.status = [];
  app.buildStatusDropdown();
  assert.equal(document.getElementById('status-filter-text').textContent, 'Status: All');
});

test('suite environment bar shows app details only when they describe one app', () => {
  const summaries = (variants) => variants.map((v) => summary('backupTest', v, { total: 1, pass: 1 }));
  const moduleNode = (name, variants) => ({
    name,
    testSuiteSummaries: summaries(variants),
    packages: [
      {
        name: 'com.example',
        testSuiteSummaries: summaries(variants),
        classes: [
          {
            name: 'BackupTest',
            testSuiteSummaries: summaries(variants),
            testCases: [
              {
                name: `restoreWorks${name}`,
                targets: [
                  {
                    name: 'default',
                    testSuiteSummaries: summaries(variants),
                    testSuiteResults: [
                      {
                        testSuiteName: 'backupTest',
                        variantResults: Object.fromEntries(variants.map((v) => [v, { status: 'pass', adapterId: 'backupRestore' }])),
                      },
                    ],
                  },
                ],
              },
            ],
          },
        ],
      },
    ],
  });
  const env = (modulePath, variantName, applicationId) => ({
    envId: `env_${applicationId}`,
    suiteName: 'backupTest',
    variantName,
    targetName: 'default',
    modulePath,
    applicationId,
  });
  const report = (modules, suiteEnvironments, variants) => ({
    projectName: 'root',
    variants,
    testSuites: ['backupTest'],
    targets: ['default'],
    numberOfModules: modules.length,
    numberOfPackages: 1,
    numberOfClasses: 1,
    suiteDescriptors: {
      backupTest: { adapterId: 'backupRestore', title: 'Backup & Restore', summaryColumns: [{ key: 'transport', label: 'Transport', filterable: true }] },
    },
    suiteEnvironments,
    modules,
  });
  const envBarText = (harness) => harness.document.getElementById('suite-env-bar').textContent;

  const oneApp = createReportHarness(report([moduleNode(':app', ['debug'])], [env(':app', 'debug', 'com.example.app')], ['debug']));
  assert.equal(oneApp.app.usesRichSingleSuiteLayout(), true);
  assert.match(envBarText(oneApp), /com\.example\.app/);

  // A list that covers two apps doesn't present either one as the app under test.
  const twoApps = createReportHarness(
    report(
      [moduleNode(':app', ['debug']), moduleNode(':wear', ['debug'])],
      [env(':app', 'debug', 'com.example.app'), env(':wear', 'debug', 'com.example.wear')],
      ['debug'],
    ),
  );
  assert.doesNotMatch(envBarText(twoApps), /com\.example\.(app|wear)/);

  // Without an environment for the selected variant, another variant's app isn't shown.
  const otherVariant = createReportHarness(
    report([moduleNode(':app', ['debug', 'release'])], [env(':app', 'debug', 'com.example.app')], ['debug', 'release']),
  );
  otherVariant.app.state.filters.variants = ['release'];
  otherVariant.app.render();
  assert.doesNotMatch(envBarText(otherVariant), /com\.example\.app/);
});

test('switching out of backupTest into unitTest, androidTest, and screenshotTest clears rich-suite DOM and classes', () => {
  const summaries = [
    summary('Aggregated', 'debug', { total: 4, pass: 4 }),
    summary('unitTest', 'debug', { total: 1, pass: 1 }),
    summary('androidTest', 'debug', { total: 1, pass: 1 }),
    summary('screenshotTest', 'debug', { total: 1, pass: 1 }),
    summary('backupTest', 'debug', { total: 1, pass: 1 }),
  ];
  const makeTc = (name, suiteName, adapterId, extra = {}) => ({
    name,
    targets: [
      {
        name: 'default',
        testSuiteSummaries: summaries,
        testSuiteResults: [
          {
            testSuiteName: suiteName,
            variantResults: { debug: { status: 'pass', durationMs: 120, adapterId, ...extra } },
          },
        ],
      },
    ],
  });
  const report = {
    projectName: ':app',
    variants: ['debug'],
    testSuites: ['Aggregated', 'unitTest', 'androidTest', 'screenshotTest', 'backupTest'],
    targets: ['default'],
    numberOfModules: 1,
    numberOfPackages: 1,
    numberOfClasses: 1,
    suiteDescriptors: {
      screenshotTest: { adapterId: 'screenshot', title: 'Screenshot Tests' },
      backupTest: {
        adapterId: 'backupRestore',
        title: 'Backup & Restore',
        summaryColumns: [{ key: 'transport', label: 'Transport', filterable: true }],
      },
    },
    suiteEnvironments: [
      { envId: 'env_1', suiteName: 'backupTest', variantName: 'debug', targetName: 'default', modulePath: ':app', applicationId: 'com.example.app' },
    ],
    modules: [
      {
        name: ':app',
        testSuiteSummaries: summaries,
        packages: [
          {
            name: 'com.example',
            testSuiteSummaries: summaries,
            classes: [
              {
                name: 'CrossSuiteTest',
                testSuiteSummaries: summaries,
                testCases: [
                  makeTc('unitCheck', 'unitTest', 'default'),
                  makeTc('deviceCheck', 'androidTest', 'default'),
                  makeTc('cardPreview', 'screenshotTest', 'screenshot', { screenshot: { golden: 'g.png', actual: 'a.png' } }),
                  makeTc('cloudRoundTrip', 'backupTest', 'backupRestore', { summaryAttributes: { transport: 'CLOUD_ENCRYPTED' } }),
                ],
              },
            ],
          },
        ],
      },
    ],
  };

  const { app, document } = createReportHarness(report);
  const selectSuite = (suiteName) => {
    app.state.filters.testSuite = suiteName;
    app.syncSortModeWithLayout();
    app.updateTestSuiteUI();
    app.render();
  };

  selectSuite('backupTest');
  assert.equal(app.usesRichSingleSuiteLayout(), true);
  assert.equal(document.body.classList.contains('rich-suite-active'), true);
  assert.notEqual(document.getElementById('header-stats-single').innerHTML, '');

  for (const classicSuite of ['unitTest', 'androidTest', 'screenshotTest', 'all']) {
    selectSuite(classicSuite);
    assert.equal(app.usesRichSingleSuiteLayout(), false, `${classicSuite} should use classic layout`);
    assert.equal(document.body.classList.contains('rich-suite-active'), false, `${classicSuite} must clear body.rich-suite-active`);
    assert.equal(document.querySelector('table').classList.contains('single-suite-table'), false, `${classicSuite} must clear table.single-suite-table`);
    assert.equal(document.getElementById('header-stats-single').innerHTML, '', `${classicSuite} must clear #header-stats-single children`);
    assert.equal(document.getElementById('suite-env-bar').innerHTML, '', `${classicSuite} must clear #suite-env-bar`);
    assert.equal(document.getElementById('search-input').placeholder, 'Search report...', `${classicSuite} must restore classic search placeholder`);
  }
});

test('savedClassicSort rebases deselected variants, detail target resolves up front, and plugin column classes/log titles stay isolated', () => {
  const mergedSummaries = [
    {
      name: 'Aggregated',
      variantSummaries: [
        { name: 'debug', total: 2, passed: 0, failed: 2, skipped: 0, rate: 0, durationMs: 200 },
        { name: 'release', total: 1, passed: 0, failed: 1, skipped: 0, rate: 0, durationMs: 100 },
      ],
    },
    {
      name: 'backupTest',
      variantSummaries: [
        { name: 'debug', total: 1, passed: 0, failed: 1, skipped: 0, rate: 0, durationMs: 100 },
        { name: 'release', total: 1, passed: 0, failed: 1, skipped: 0, rate: 0, durationMs: 100 },
      ],
    },
    summary('customRich', 'debug', { total: 1, fail: 1 }),
  ];
  const report = {
    projectName: ':app',
    variants: ['debug', 'release'],
    testSuites: ['Aggregated', 'backupTest', 'customRich'],
    targets: ['default'],
    numberOfModules: 1,
    numberOfPackages: 1,
    numberOfClasses: 1,
    suiteDescriptors: {
      backupTest: {
        adapterId: 'backupRestore',
        title: 'Backup & Restore',
        summaryColumns: [{ key: 'transport', label: 'Transport', filterable: true }, { key: 'devices', label: 'Devices' }],
      },
      customRich: {
        adapterId: 'default',
        title: 'Custom Rich Suite',
        summaryColumns: [{ key: 'transport', label: 'Transport' }],
      },
    },
    suiteEnvironments: [],
    modules: [
      {
        name: ':app',
        testSuiteSummaries: mergedSummaries,
        packages: [
          {
            name: 'com.example',
            testSuiteSummaries: mergedSummaries,
            classes: [
              {
                name: 'MultiVarBackupTest',
                testSuiteSummaries: mergedSummaries,
                testCases: [
                  {
                    name: 'cloudMigration',
                    targets: [
                      {
                        name: 'default',
                        testSuiteSummaries: mergedSummaries,
                        testSuiteResults: [
                          {
                            testSuiteName: 'backupTest',
                            variantResults: {
                              debug: {
                                status: 'fail',
                                adapterId: 'backupRestore',
                                detailId: 'pending_backup',
                                summaryAttributes: { transport: { value: 'CLOUD', displayValue: 'Cloud' } },
                                logMeta: [{ title: 'Host log', lineCount: 10, artifactPath: 'artifacts/t1/host-log.txt' }],
                              },
                              release: {
                                status: 'fail',
                                adapterId: 'backupRestore',
                                summaryAttributes: { transport: { value: 'CLOUD', displayValue: 'Cloud' } },
                              },
                            },
                          },
                          {
                            testSuiteName: 'customRich',
                            variantResults: {
                              debug: {
                                status: 'fail',
                                adapterId: 'default',
                                detailId: 'pending_custom',
                                summaryAttributes: { transport: { value: 'GRPC', displayValue: 'gRPC' } },
                              },
                            },
                          },
                        ],
                      },
                    ],
                  },
                ],
              },
            ],
          },
        ],
      },
    ],
  };

  // Use missing lazy details so isLoading stays true synchronously during initial render.
  const { app, document, SuiteRegistry } = createReportHarness(report, {});

  assert.equal(SuiteRegistry.resolve('backupRestore').getSummaryColumnClass({ key: 'transport' }), 'col-transport');
  assert.equal(SuiteRegistry.resolve('default').getSummaryColumnClass({ key: 'transport' }), 'col-dynamic');
  assert.equal(SuiteRegistry.resolve('default').getSummaryColumnClass({ key: 'devices' }), 'col-devices');

  // Sort by debug.fail in classic matrix, enter rich single-suite list on debug, switch variant to release, then exit to (all).
  app.state.filters.testSuite = 'backupTest';
  app.state.filters.variants = ['debug'];
  app.state.sort = { by: 'debug.fail', order: 'desc' };
  app.syncSortModeWithLayout();
  assert.equal(app.state.sort.by, 'status');

  app.state.filters.variants = ['release'];
  app.syncSortModeWithLayout();
  app.state.filters.testSuite = 'all';
  app.syncSortModeWithLayout();
  assert.equal(app.state.sort.by, 'release.fail');
  assert.equal(app.state.sort.order, 'desc');

  // Opening a rich run from (all) resolves detailViewMode='rich' up front and uses logMeta[0].title when single-log, or 'Logs' otherwise.
  app.state.filters.variants = ['debug'];
  app.state.filters.testSuite = 'backupTest';
  const tcNode = app.processedData.modules[0].packages[0].classes[0].testCases[0];
  app.state.filters.testSuite = 'all';
  app.showStackTraceView(tcNode, { moduleName: ':app', packageName: 'com.example', className: 'MultiVarBackupTest' });
  assert.equal(app.state.detailViewMode, 'rich');
  assert.equal(document.body.classList.contains('rich-suite-active'), true);
  assert.match(document.getElementById('stack-trace-grid').textContent, /Host log/);

  app.state.filters.testSuite = 'customRich';
  app.render();
  assert.match(document.getElementById('stack-trace-grid').textContent, /Logs/);
});

test('multi-target rich search keeps status-tab counts in sync, drops empty target sets, and strips XML comments in backup rules', () => {
  const sumBoth = [
    summary('Aggregated', 'debug', { total: 2, pass: 1, fail: 1 }),
    summary('backupTest', 'debug', { total: 2, pass: 1, fail: 1 }),
  ];
  const sumPass = [
    summary('Aggregated', 'debug', { total: 1, pass: 1 }),
    summary('backupTest', 'debug', { total: 1, pass: 1 }),
  ];
  const sumFail = [
    summary('Aggregated', 'debug', { total: 1, fail: 1 }),
    summary('backupTest', 'debug', { total: 1, fail: 1 }),
  ];
  const report = {
    projectName: ':app',
    variants: ['debug'],
    testSuites: ['Aggregated', 'backupTest'],
    targets: ['pixel6', 'pixel8'],
    numberOfModules: 1,
    numberOfPackages: 1,
    numberOfClasses: 1,
    suiteDescriptors: {
      backupTest: {
        adapterId: 'backupRestore',
        title: 'Backup & Restore',
        summaryColumns: [{ key: 'transport', label: 'Transport', filterable: true }],
      },
    },
    suiteEnvironments: [],
    modules: [
      {
        name: ':app',
        testSuiteSummaries: sumBoth,
        packages: [
          {
            name: 'com.example',
            testSuiteSummaries: sumBoth,
            classes: [
              {
                name: 'MultiTargetBackupTest',
                testSuiteSummaries: sumBoth,
                testCases: [
                  {
                    name: 'cloudPassOnly',
                    targets: [
                      {
                        name: 'pixel6',
                        testSuiteSummaries: sumPass,
                        testSuiteResults: [
                          {
                            testSuiteName: 'backupTest',
                            variantResults: {
                              debug: {
                                status: 'pass',
                                adapterId: 'backupRestore',
                                summaryAttributes: { transport: { value: 'CLOUD', displayValue: 'Cloud' } },
                              },
                            },
                          },
                        ],
                      },
                    ],
                  },
                  {
                    name: 'd2dFailsOnPixel8',
                    targets: [
                      {
                        name: 'pixel8',
                        testSuiteSummaries: sumFail,
                        testSuiteResults: [
                          {
                            testSuiteName: 'backupTest',
                            variantResults: {
                              debug: {
                                status: 'fail',
                                adapterId: 'backupRestore',
                                summaryAttributes: { transport: { value: 'DEVICE_TO_DEVICE', displayValue: 'Device to device' } },
                              },
                            },
                          },
                        ],
                      },
                    ],
                  },
                ],
              },
            ],
          },
        ],
      },
    ],
  };

  const { app, document, SuiteRegistry, UIUtils } = createReportHarness(report);
  app.state.filters.testSuite = 'backupTest';
  app.syncSortModeWithLayout();

  // Search matches cloudPassOnly, while status filter ['failed'] filters out its only target (pixel6): must NOT render UNKNOWN row.
  app.state.filters.search = 'cloudPassOnly';
  app.state.filters.status = ['failed'];
  app.render();
  const rowsHtml = document.getElementById('results-data').innerHTML;
  assert.doesNotMatch(rowsHtml, /UNKNOWN/);
  assert.match(rowsHtml, /No tests match the current filters/);

  // Searching by target name ('pixel8') updates status-segments counts to match the 1 visible target row.
  app.state.filters.status = ['passed', 'failed', 'skipped'];
  app.state.filters.search = 'pixel8';
  app.render();
  assert.match(document.getElementById('status-segments').textContent, /All\s*1/);
  assert.match(document.getElementById('status-segments').textContent, /Failed\s*1/);

  // BackupSuitePlugin ignores commented-out <device-transfer> and commented-out <exclude> lines.
  const backupPlugin = SuiteRegistry.resolve('backupRestore');
  const templateDer = {
    key: 'dataExtractionRules',
    label: 'data_extraction_rules.xml',
    content: [
      '<data-extraction-rules>',
      '  <cloud-backup>',
      '    <!-- TODO: <exclude domain="sharedpref" path="auth.xml"/> -->',
      '    <exclude domain="sharedpref" path="auth.xml"/>',
      '  </cloud-backup>',
      '  <!--',
      '  <device-transfer>',
      '    <exclude domain="sharedpref" path="auth.xml"/>',
      '  </device-transfer>',
      '  -->',
      '</data-extraction-rules>',
    ].join('\n'),
  };
  const legacyFbc = { key: 'fullBackupContent', label: 'backup_rules.xml', content: '<full-backup-content/>' };
  const selectedD2dApi30 = backupPlugin.selectActiveRuleFiles([templateDer, legacyFbc], { targetSdkVersion: 30 }, { transport: 'DEVICE_TO_DEVICE' });
  assert.equal(selectedD2dApi30.length, 1);
  assert.equal(selectedD2dApi30[0].key, 'fullBackupContent');

  const selectedD2dApi34 = backupPlugin.selectActiveRuleFiles([templateDer, legacyFbc], { targetSdkVersion: 34 }, { transport: 'DEVICE_TO_DEVICE' });
  assert.equal(selectedD2dApi34.length, 0);

  const rendered = backupPlugin.renderRuleBlocks({
    lazyFiles: [templateDer],
    configFiles: [],
    allowBadgeHtml: '',
    likelyCause: 'data_extraction_rules.xml excludes domain="sharedpref" path="auth.xml" for <cloud-backup>.',
    UIUtils,
  });
  const highlights = rendered.blocksHtml.join('\n').match(/class="detail-rule-highlight"/g) || [];
  assert.equal(highlights.length, 1, 'Only the non-commented <exclude> in <cloud-backup> should be highlighted');

  // Sorting by target in a multi-target rich suite restores savedClassicSort when exiting to (all).
  app.state.filters.search = '';
  app.state.filters.testSuite = 'all';
  app.state.sort = { by: 'debug.fail', order: 'desc' };
  app.state.filters.testSuite = 'backupTest';
  app.syncSortModeWithLayout();
  app.state.sort = { by: 'target', order: 'asc' };
  app.state.filters.testSuite = 'all';
  app.syncSortModeWithLayout();
  assert.equal(app.state.sort.by, 'debug.fail');
  assert.equal(app.state.sort.order, 'desc');

  // Opening a detail view records listHistoryDepth in state and restores focus onto the newly rendered trigger row on return.
  app.state.filters.testSuite = 'backupTest';
  app.syncSortModeWithLayout();
  app.render();
  const triggerRow = document.getElementById('results-data').querySelector('.stack-trace-trigger');
  assert.ok(triggerRow);
  triggerRow.click();
  assert.equal(typeof app.state.listHistoryDepth, 'number');
  app.state.listHistoryDepth = null;
  app.returnFromDetailView();
  assert.equal(app.state.currentView, 'report');
  assert.equal(document.activeElement?.dataset?.testCase, triggerRow.dataset.testCase);
});

test('single-suite header stats ignore status tab filter and share one pass with status tabs', () => {
  const mkCase = (name, status, transport, transportLabel, durationMs) => ({
    name,
    targets: [
      {
        name: 'default',
        testSuiteSummaries: [
          summary('backupTest', 'debug', {
            total: 1,
            pass: status === 'pass' ? 1 : 0,
            fail: status === 'fail' ? 1 : 0,
            durationMs,
          }),
        ],
        testSuiteResults: [
          {
            testSuiteName: 'backupTest',
            variantResults: {
              debug: {
                status,
                adapterId: 'backupRestore',
                durationMs,
                summaryAttributes: {
                  transport: { label: 'Transport', value: transport, displayValue: transportLabel },
                },
              },
            },
          },
        ],
      },
    ],
  });
  const suiteSummary = [summary('backupTest', 'debug', { total: 3, pass: 2, fail: 1, durationMs: 5000 })];
  const report = {
    projectName: ':app',
    variants: ['debug'],
    testSuites: ['backupTest'],
    targets: ['default'],
    numberOfModules: 1,
    numberOfPackages: 1,
    numberOfClasses: 1,
    suiteDescriptors: {
      backupTest: {
        suiteName: 'backupTest',
        adapterId: 'backupRestore',
        summaryColumns: [{ key: 'transport', label: 'Transport', filterable: true }],
      },
    },
    suiteEnvironments: [],
    modules: [
      {
        name: ':app',
        testSuiteSummaries: suiteSummary,
        packages: [
          {
            name: 'com.example',
            testSuiteSummaries: suiteSummary,
            classes: [
              {
                name: 'HeaderStatsTest',
                testSuiteSummaries: suiteSummary,
                testCases: [
                  mkCase('cloudPass', 'pass', 'CLOUD_ENCRYPTED', 'Cloud (encrypted)', 1200),
                  mkCase('cloudFail', 'fail', 'CLOUD_ENCRYPTED', 'Cloud (encrypted)', 2800),
                  mkCase('localPass', 'pass', 'LOCAL', 'Local', 1000),
                ],
              },
            ],
          },
        ],
      },
    ],
  };

  const { app, document } = createReportHarness(report);
  const readHeader = () => ({
    passRate: document.getElementById('single-pass-rate').textContent,
    total: document.getElementById('single-total-tests').textContent,
    failed: document.getElementById('single-failed-tests').textContent,
    duration: document.getElementById('single-total-duration').textContent,
  });

  assert.deepEqual(readHeader(), { passRate: '66.7%', total: '3', failed: '1', duration: '5.0 s' });

  // Clicking the Failed status tab filters rows without distorting header totals or pass rate.
  const failedTab = document.getElementById('status-segments').querySelector('[data-status-tab="failed"]');
  assert.ok(failedTab);
  failedTab.click();
  assert.deepEqual(readHeader(), { passRate: '66.7%', total: '3', failed: '1', duration: '5.0 s' });
  assert.match(document.getElementById('results-data').textContent, /cloudFail/);
  assert.doesNotMatch(document.getElementById('results-data').textContent, /cloudPass|localPass/);
  assert.match(document.getElementById('status-segments').textContent, /All\s*3/);
  assert.match(document.getElementById('status-segments').textContent, /Failed\s*1/);
  assert.match(document.getElementById('status-segments').textContent, /Passed\s*2/);

  // Clicking the Passed status tab likewise keeps the true suite totals and failed count in the header.
  const passedTab = document.getElementById('status-segments').querySelector('[data-status-tab="passed"]');
  assert.ok(passedTab);
  passedTab.click();
  assert.deepEqual(readHeader(), { passRate: '66.7%', total: '3', failed: '1', duration: '5.0 s' });
  assert.match(document.getElementById('results-data').textContent, /cloudPass/);
  assert.match(document.getElementById('results-data').textContent, /localPass/);
  assert.doesNotMatch(document.getElementById('results-data').textContent, /cloudFail/);

  // Attribute filters update both header stats and status tabs in a single computeActiveSummaryStats() pass.
  let statsCalls = 0;
  const origCompute = app.computeActiveSummaryStats.bind(app);
  app.computeActiveSummaryStats = () => {
    statsCalls++;
    return origCompute();
  };
  app.state.filters.suiteAttributes = { transport: 'CLOUD_ENCRYPTED' };
  app.render();
  assert.equal(statsCalls, 1);
  assert.deepEqual(readHeader(), { passRate: '50%', total: '2', failed: '1', duration: '4.0 s' });
  assert.match(document.getElementById('status-segments').textContent, /All\s*2/);
  assert.match(document.getElementById('status-segments').textContent, /Failed\s*1/);
  assert.match(document.getElementById('status-segments').textContent, /Passed\s*1/);
  assert.match(document.getElementById('results-data').textContent, /cloudPass/);
  assert.doesNotMatch(document.getElementById('results-data').textContent, /cloudFail|localPass/);
});
