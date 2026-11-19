const input = document.querySelector('#find');
const results = document.querySelector('#results');
const reading = document.querySelector('#reading');
const hits = document.querySelector('#hits');
const contents = document.querySelector('#contents');
const compact = matchMedia('(max-width: 800px)');
const sections = [...document.querySelectorAll('.chapter')];
const entries = [];

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
    addEntry(row, label, row.cells[1].textContent + ' · ' + row.cells[2].textContent, title);
  }
  for (const term of chapter.querySelectorAll('dt')) {
    term.id = 'topic-' + term.textContent.toLowerCase().replace(/[^a-z0-9]+/g, '-').replace(/-$/, '');
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
  document.querySelector('#count').textContent = matches.length ? matches.length + (matches.length === 1 ? ' match' : ' matches') : 'No matches. Try a JSON field name or a shorter keyword.';
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

function selectSection() {
  const target = document.getElementById(location.hash.slice(1));
  const current = target?.closest('.chapter')?.id;
  for (const link of contents.querySelectorAll('a')) {
    if (link.hash === '#' + current) link.setAttribute('aria-current', 'location');
    else link.removeAttribute('aria-current');
  }
}

document.querySelector('.search').hidden = false;
contents.open = !compact.matches;
compact.addEventListener('change', () => { contents.open = !compact.matches; });
input.addEventListener('input', () => {
  showResults();
  if (input.value.trim()) document.documentElement.scrollTop = 0;
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
  if (!results.hidden) clearSearch();
  if (compact.matches) contents.open = false;
});
window.addEventListener('hashchange', () => { clearSearch(); selectSection(); });
selectSection();
