const input = document.querySelector('#find');
const results = document.querySelector('#results');
const reading = document.querySelector('#reading');
const hits = document.querySelector('#hits');
const contents = document.querySelector('#contents');
const compact = matchMedia('(max-width: 800px)');
const sections = [...document.querySelectorAll('.chapter')];
const chapterLinks = [...contents.querySelectorAll('.nav-group a')];
const mobileChapter = document.querySelector('#mobile-chapter');
const entries = [];
const chinese = document.documentElement.lang === 'zh-CN';
const languageLink = document.querySelector('.language-link');
const alternatePage = languageLink.getAttribute('href');

function addEntry(node, label, description, section) {
  const entry = { id: node.id, label, description, section };
  entry.key = (label + ' ' + section + ' ' + description).toLowerCase();
  entries.push(entry);
}

for (const chapter of sections) {
  const title = chapter.querySelector('h2').textContent;
  addEntry(chapter, title, chapter.querySelector('.intro').textContent, '');
  for (const heading of chapter.querySelectorAll('h3')) {
    addEntry(heading, heading.textContent, '', title);
  }
  for (const row of chapter.querySelectorAll('tr[id]')) {
    const label = [...row.querySelectorAll('th code')].map(code => code.textContent).join(' / ');
    const values = [...row.cells[1].childNodes].map(node => node.textContent).join(' ');
    const group = row.closest('.parameter-group')?.querySelector('h3');
    addEntry(row, label, values + ' · ' + row.cells[2].textContent, group ? title + ' › ' + group.textContent : title);
  }
  for (const term of chapter.querySelectorAll('dt')) {
    addEntry(term, term.textContent, term.nextElementSibling.textContent, title);
  }
}

function showResults() {
  const words = input.value.trim().toLowerCase().split(/\s+/).filter(Boolean);
  reading.hidden = words.length > 0;
  results.hidden = !reading.hidden;
  hits.replaceChildren();
  if (results.hidden) return;
  const matches = entries.filter(entry => words.every(word => entry.key.includes(word)));
  matches.sort((a, b) => Number(b.label.toLowerCase().includes(words[0])) - Number(a.label.toLowerCase().includes(words[0])));
  document.querySelector('#count').textContent = matches.length
    ? matches.length + (chinese ? ' 个结果' : matches.length === 1 ? ' match' : ' matches')
    : chinese ? '没有找到结果。试试 JSON 字段名或更短的关键词。' : 'No matches. Try a JSON field name or a shorter keyword.';
  for (const entry of matches) {
    const li = document.createElement('li');
    const link = document.createElement('a');
    link.href = '#' + entry.id;
    link.textContent = entry.label;
    const p = document.createElement('p');
    p.textContent = [entry.section, entry.description].filter(Boolean).join(' · ');
    li.append(link, p);
    hits.append(li);
  }
}

function clearSearch() {
  input.value = '';
  showResults();
}

function markChapter() {
  const offset = compact.matches
    ? contents.querySelector('summary').getBoundingClientRect().bottom + 40
    : document.querySelector('.top').getBoundingClientRect().bottom + 40;
  const chapter = reading.hidden ? null : sections.findLast(section => section.getBoundingClientRect().top <= offset);
  const anchor = document.getElementById(location.hash.slice(1));
  const target = chapter && anchor?.closest('.chapter') === chapter ? anchor : chapter;
  languageLink.href = alternatePage + (target ? '#' + target.id : '');
  mobileChapter.textContent = chapter?.querySelector('h2').textContent || '';
  for (const link of chapterLinks) {
    if (chapter && link.hash === '#' + chapter.id) link.setAttribute('aria-current', 'location');
    else link.removeAttribute('aria-current');
  }
}

for (const sample of document.querySelectorAll('figure.sample')) {
  const code = sample.querySelector('pre code');
  const json = code.textContent;
  const tokens = /"(?:\\.|[^"\\])*"|-?\b\d+(?:\.\d+)?|\b(?:true|false|null)\b/g;
  const highlighted = document.createDocumentFragment();
  let end = 0;
  for (const match of json.matchAll(tokens)) {
    highlighted.append(json.slice(end, match.index));
    const token = document.createElement('span');
    const [value] = match;
    end = match.index + value.length;
    const isKey = json.slice(end).trimStart().startsWith(':');
    token.className = isKey ? 'token-key' : value.startsWith('"') ? 'token-string' : 'token-value';
    token.textContent = value;
    highlighted.append(token);
  }
  highlighted.append(json.slice(end));
  code.replaceChildren(highlighted);

  const copy = document.createElement('button');
  copy.type = 'button';
  copy.className = 'copy';
  copy.textContent = chinese ? '复制 JSON' : 'Copy JSON';
  copy.setAttribute('aria-live', 'polite');
  let resetLabel;
  copy.addEventListener('click', async () => {
    try {
      await navigator.clipboard.writeText(json);
      copy.textContent = chinese ? '已复制 ✓' : 'Copied ✓';
    } catch {
      const range = document.createRange();
      range.selectNodeContents(code);
      const selection = window.getSelection();
      selection.removeAllRanges();
      selection.addRange(range);
      copy.textContent = chinese ? '已选中 JSON' : 'JSON selected';
    }
    clearTimeout(resetLabel);
    resetLabel = setTimeout(() => { copy.textContent = chinese ? '复制 JSON' : 'Copy JSON'; }, 2200);
  });
  sample.querySelector('figcaption').append(copy);
}

document.querySelector('.search').hidden = false;
contents.open = !compact.matches;
compact.addEventListener('change', () => { contents.open = !compact.matches; });
input.addEventListener('input', () => {
  showResults();
  if (input.value.trim()) {
    if (compact.matches) contents.open = false;
    document.documentElement.scrollTop = 0;
  }
  markChapter();
});
input.addEventListener('keydown', event => {
  if (event.key === 'Escape') clearSearch();
});
document.querySelector('#clear').addEventListener('click', () => {
  clearSearch();
  input.focus();
});
document.addEventListener('click', event => {
  const link = event.target.closest('a[href^="#"]');
  if (!link) return;
  const fromSearch = hits.contains(link);
  if (!results.hidden) clearSearch();
  if (compact.matches) contents.open = false;
  if (fromSearch) {
    const target = document.getElementById(link.hash.slice(1));
    target.setAttribute('tabindex', '-1');
    target.focus({ preventScroll: true });
  }
});
document.addEventListener('keydown', event => {
  if (event.key === '/' && !event.altKey && !event.ctrlKey && !event.metaKey
      && !event.target.closest('input, textarea, select, [contenteditable="true"]')) {
    event.preventDefault();
    input.focus();
  }
});
let scrollFrame = 0;
function queueChapterUpdate() {
  if (!scrollFrame) scrollFrame = requestAnimationFrame(() => {
    scrollFrame = 0;
    markChapter();
  });
}
window.addEventListener('scroll', queueChapterUpdate, { passive: true });
window.addEventListener('resize', queueChapterUpdate);
window.addEventListener('hashchange', () => { clearSearch(); queueChapterUpdate(); });
queueChapterUpdate();
