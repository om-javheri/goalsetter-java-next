// @ts-nocheck: the same scripts the HTML site runs, kept as they are.

/** Reveals entrance animations as they scroll into view. */
export function startMotion(): void {
  var els=[].slice.call(document.querySelectorAll('[data-uide-anim]'));var nums=[].slice.call(document.querySelectorAll('[data-uide-count]'));if(!els.length&&!nums.length)return;if(els.length)document.documentElement.classList.add('uide-anim-ready');var still=window.matchMedia&&window.matchMedia('(prefers-reduced-motion: reduce)').matches;if(!('IntersectionObserver' in window)){els.forEach(function(e){e.setAttribute('data-uide-in','')});return}function count(e){var t=e.textContent,m=/^(\D*?)(\d[\d,]*(?:\.\d+)?)(.*)$/.exec(t);if(!m)return;var end=parseFloat(m[2].replace(/,/g,'')),dec=(m[2].split('.')[1]||'').length,comma=m[2].indexOf(',')>=0,t0=0;function fmt(v){var s=v.toFixed(dec);if(comma)s=s.replace(/\B(?=(\d{3})+(?!\d))/g,',');return m[1]+s+m[3]}function step(ts){if(!t0)t0=ts;var k=Math.min(1,(ts-t0)/1200);e.textContent=k<1?fmt(end*(1-Math.pow(1-k,3))):t;if(k<1)requestAnimationFrame(step)}e.textContent=fmt(0);requestAnimationFrame(step)}var io=new IntersectionObserver(function(entries){entries.forEach(function(e){if(!e.isIntersecting)return;if(e.target.hasAttribute('data-uide-count')){if(!still)count(e.target)}if(e.target.hasAttribute('data-uide-anim'))e.target.setAttribute('data-uide-in','');io.unobserve(e.target)})},{rootMargin:'0px 0px -10% 0px'});els.concat(nums).forEach(function(e){io.observe(e)})
}

/** Opens and closes folding menus on small screens. */
export function startMenus(): void {
  function set(m,o){if(o)m.setAttribute('data-uide-open','');else m.removeAttribute('data-uide-open');var t=m.querySelector('.uide-menu-toggle');if(t)t.setAttribute('aria-expanded',String(o))}document.addEventListener('click',function(e){var el=e.target;if(!el||!el.closest)return;var t=el.closest('.uide-menu-toggle');if(t){var m=t.parentNode;set(m,!m.hasAttribute('data-uide-open'));return}var a=el.closest('.uide-menu a');if(a)set(a.closest('.uide-menu'),false)});document.addEventListener('keydown',function(e){if(e.key!=='Escape')return;[].forEach.call(document.querySelectorAll('.uide-menu[data-uide-open]'),function(m){set(m,false)})})
}

/** Sends forms that have somewhere to send, and shows their thank-you text. */
export function startForms(): void {
  document.addEventListener('submit',function(e){var f=e.target;if(!f||!f.matches||!f.matches('form[data-uide-form]'))return;e.preventDefault();if(f.hasAttribute('data-uide-busy')||!f.action)return;var els=[].slice.call(f.querySelectorAll('.uide-input, .uide-textarea'));var s=f.querySelector(':scope > .uide-form-status');if(!s){s=document.createElement('p');s.className='uide-form-status';s.setAttribute('role','status');f.appendChild(s)}els.forEach(function(el){el.removeAttribute('aria-invalid')});var k=f.getAttribute('data-uide-form');if(k==='record')return;if(/^sign(up|in|out)$/.test(k)){var u=new URL(f.action,location.href),key='uide-member:'+u.host+u.pathname.replace(/\/members\/[a-z]+$/,''),tk=null;try{tk=localStorage.getItem(key)}catch(x){}var of=function(t){return els.filter(function(el){return el.getAttribute('type')===t})[0]},nm=els.filter(function(el){var t=el.getAttribute('type');return t!=='email'&&t!=='password'})[0],em=of('email'),pw=of('password'),body=k==='signout'?{token:tk}:{email:em?em.value.trim():'',password:pw?pw.value:''};if(k==='signup'&&nm&&nm.value.trim())body.name=nm.value.trim();f.setAttribute('data-uide-busy','');var fail=function(){f.removeAttribute('data-uide-busy');s.textContent="Sorry, your message couldn’t be sent. Please try again."};fetch(f.action,{method:'POST',headers:{'Content-Type':'text/plain'},body:JSON.stringify(body),credentials:'same-origin'}).then(function(r){return r.json().catch(function(z){return{}}).then(function(d){f.removeAttribute('data-uide-busy');try{if(r.ok&&k==='signout')localStorage.removeItem(key);else if(r.ok&&d.token)localStorage.setItem(key,d.token)}catch(x){}s.textContent=r.ok&&d.pending?d.message:r.ok?(f.getAttribute('data-uide-success')||(k==='signout'?'Signed out.':d.message||'Signed in.')):(d.message||"Sorry, your message couldn’t be sent. Please try again.");if(r.ok)f.reset();if(r.ok&&!d.pending){var n=f.getAttribute('data-uide-next');if(n)location.assign(n);else location.reload()}})},fail);return}if(els.every(function(el){return!el.value.trim()})){s.textContent="Fill in the form first.";return}var bad=els.filter(function(el){return el.getAttribute('type')==='email'&&el.value.trim()&&!/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(el.value.trim())})[0];if(bad){bad.setAttribute('aria-invalid','true');bad.focus();s.textContent="Please enter a valid email address.";return}var fields=[].map.call(f.querySelectorAll('.uide-input, .uide-textarea'),function(el,i){return{label:el.getAttribute('aria-label')||'Field '+(i+1),value:el.value}});var owner=f.getAttribute('data-uide-form')==='owner';var init;if(owner)init={method:'POST',headers:{'Content-Type':'text/plain'},body:JSON.stringify({fields:fields,page:location.pathname})};else{var d={};fields.forEach(function(x){d[x.label]=x.value});init={method:'POST',headers:{'Content-Type':'application/json',Accept:'application/json'},body:JSON.stringify(d)}}f.setAttribute('data-uide-busy','');function done(ok){f.removeAttribute('data-uide-busy');if(ok){f.setAttribute('data-uide-sent','');f.reset()}s.textContent=ok?(f.getAttribute('data-uide-success')||"Thank you! Your message has been sent."):"Sorry, your message couldn’t be sent. Please try again."}fetch(f.action,init).then(function(r){done(r.ok)},function(){done(false)})})
}

/** Lets visitors search and narrow lists. */
export function startLists(): void {
  (function startLists(doc) {
    const fold = (s) => s.toLowerCase().normalize('NFD').replace(/[\u0300-\u036f]/g, '');
    const byKey = (attr, key) => [].slice.call(doc.querySelectorAll('[' + attr + ']')).filter((el) => el.getAttribute(attr) === key)[0];
    // Now, written as dates are stored ("2026-10-26T09:30"): a date alone is past once its day is over.
    const pad = (n) => (n < 10 ? '0' : '') + n;
    const d = new Date();
    const today = d.getFullYear() + '-' + pad(d.getMonth() + 1) + '-' + pad(d.getDate());
    const now = today + 'T' + pad(d.getHours()) + ':' + pad(d.getMinutes());
    const past = (el) => {
        const when = el.getAttribute('data-uide-when');
        if (!when)
            return false;
        return when.length <= 10 ? when < today : when.slice(0, 16) < now;
    };
    const update = (key) => {
        const list = byKey('data-uide-list', key);
        if (!list)
            return;
        const controls = byKey('data-uide-list-for', key);
        const more = byKey('data-uide-more', key);
        const box = controls ? controls.querySelector('[data-uide-search]') : null;
        const words = box ? fold(box.value).split(/\s+/).filter(Boolean) : [];
        const pressed = controls ? controls.querySelector('[data-uide-chip][aria-pressed="true"]') : null;
        const choice = pressed ? pressed.getAttribute('data-uide-chip') || '' : '';
        const page = Number(list.getAttribute('data-uide-page')) || 0;
        const limit = page ? Number(list.getAttribute('data-uide-shown')) || page : Infinity;
        const copies = [].slice.call(list.querySelectorAll(':scope > [data-uide-rec]'));
        // A record may be drawn as several pieces: it is shown when its text, all pieces together, matches.
        const texts = new Map();
        for (const el of copies) {
            const rec = el.getAttribute('data-uide-rec') || '';
            texts.set(rec, (texts.get(rec) || '') + ' ' + fold(el.textContent || ''));
        }
        const matching = [];
        for (const el of copies) {
            const rec = el.getAttribute('data-uide-rec') || '';
            const text = texts.get(rec) || '';
            if (matching.indexOf(rec) < 0 && !past(el) && words.every((w) => text.indexOf(w) >= 0) && (!choice || el.getAttribute('data-uide-k') === choice))
                matching.push(rec);
        }
        const shown = matching.slice(0, limit);
        for (const el of copies) {
            if (shown.indexOf(el.getAttribute('data-uide-rec') || '') >= 0)
                el.removeAttribute('hidden');
            else
                el.setAttribute('hidden', '');
        }
        if (more) {
            if (matching.length > shown.length)
                more.removeAttribute('hidden');
            else
                more.setAttribute('hidden', '');
        }
        // "Showing 3 of 8" (docs/81): of the records still to come.
        const count = controls ? controls.querySelector('[data-uide-list-count]') : null;
        if (count) {
            let total = 0;
            texts.forEach((_t, rec) => {
                if (copies.some((el) => el.getAttribute('data-uide-rec') === rec && !past(el)))
                    total++;
            });
            const next = 'Showing ' + shown.length + ' of ' + total;
            if (count.textContent !== next)
                count.textContent = next;
        }
        const empty = controls ? controls.querySelector('[data-uide-list-empty]') : null;
        if (empty) {
            if (texts.size && !matching.length)
                empty.setAttribute('data-uide-on', '');
            else
                empty.removeAttribute('data-uide-on');
        }
    };
    /** Moves the list's copies into the order its "Sort by" choice names (records' pieces stay together). */
    const order = (key) => {
        const list = byKey('data-uide-list', key);
        const controls = byKey('data-uide-list-for', key);
        const choice = controls ? controls.querySelector('[data-uide-sort]') : null;
        if (!list || !choice)
            return;
        const at = choice.selectedIndex < 0 ? 0 : choice.selectedIndex;
        const copies = [].slice.call(list.querySelectorAll(':scope > [data-uide-rec]'));
        const place = (el) => Number((el.getAttribute('data-uide-rank') || '').split(' ')[at]) || 0;
        const sorted = copies.map((el, i) => ({ el, i })).sort((a, b) => place(a.el) - place(b.el) || a.i - b.i);
        for (const s of sorted)
            list.appendChild(s.el);
    };
    /** Searching or choosing starts again from the first page. */
    const restart = (key) => {
        const list = byKey('data-uide-list', key);
        if (list)
            list.removeAttribute('data-uide-shown');
        update(key);
    };
    const editing = (el) => !!el.closest('[data-uide-edit]');
    const win = doc.defaultView || window;
    const absolute = (url) => new URL(url, doc.location.href).href;
    const token = (url) => {
        try {
            const u = new URL(url, doc.location.href);
            return win.localStorage.getItem('uide-member:' + u.host + u.pathname.replace(/\/(members|app)\/.*$/, ''));
        }
        catch {
            return null;
        }
    };
    const call = (url, method, body, json) => {
        const headers = {};
        const t = token(url);
        if (t)
            headers.Authorization = 'Bearer ' + t;
        if (body !== undefined)
            headers['Content-Type'] = json ? 'application/json' : 'text/plain';
        return win
            .fetch(url, { method, headers, credentials: 'same-origin', ...(body !== undefined && { body: JSON.stringify(body) }) })
            .then((r) => r.json().catch(() => ({})).then((d) => ({ ok: r.ok, data: d })))
            .catch(() => ({ ok: false, data: {} }));
    };
    const months = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'];
    const shown = (v, date) => {
        if (v === null || v === undefined)
            return '';
        if (typeof v === 'boolean')
            return v ? 'Yes' : 'No';
        const text = String(v);
        const m = date ? /^(\d{4})-(\d{2})-(\d{2})(?:T(\d{2}):(\d{2}))?/.exec(text) : null;
        return m && Number(m[2]) >= 1 && Number(m[2]) <= 12 ? Number(m[3]) + ' ' + months[Number(m[2]) - 1] + ' ' + m[1] + (m[4] ? ', ' + m[4] + ':' + m[5] : '') : text;
    };
    /** Fills one drawn part from a record: texts as text, addresses and pictures only when safe. */
    const fillIn = (el, rec) => {
        (el.getAttribute('data-uide-fill') || '').split(';').forEach((part) => {
            const [prop, spec] = part.split('=');
            if (!prop || !spec)
                return;
            const [field, kind] = spec.split('@');
            const value = shown(rec.values[field], kind === 'date');
            if (prop === 'text' || prop === 'label') {
                const parts = [].slice.call(el.children).filter((x) => x.tagName !== 'I');
                (parts.length ? parts[parts.length - 1] : el).textContent = value;
            }
            else if (prop === 'href') {
                if (/^(https?:\/\/|\/|#|mailto:|tel:)/i.test(value))
                    el.setAttribute('href', value);
            }
            else if (prop === 'src') {
                if (/^(https:\/\/|\/)/i.test(value))
                    el.setAttribute('src', value);
            }
            else if (prop === 'alt')
                el.setAttribute('alt', value);
        });
    };
    const records = new Map();
    const drawRecords = (list, recs) => {
        const key = list.getAttribute('data-uide-list') || '';
        records.set(key, recs);
        [].forEach.call(list.querySelectorAll(':scope > [data-uide-rec]'), (el) => el.remove());
        const templates = [].slice.call(list.querySelectorAll(':scope > [data-uide-template]'));
        for (const rec of recs) {
            for (const t of templates) {
                const copy = t.cloneNode(true);
                copy.removeAttribute('data-uide-template');
                copy.setAttribute('data-uide-rec', rec.id);
                [copy, ...[].slice.call(copy.querySelectorAll('[id]'))].forEach((x) => x.removeAttribute('id'));
                [copy, ...[].slice.call(copy.querySelectorAll('[data-uide-fill]'))].forEach((x) => fillIn(x, rec));
                // Only the member's own records can be changed or deleted.
                [].forEach.call(copy.querySelectorAll('[data-uide-record-action]'), (b) => (rec.mine ? b.removeAttribute('hidden') : b.setAttribute('hidden', '')));
                list.appendChild(copy);
            }
        }
        update(key);
    };
    const loadRecords = (list) => {
        const url = list.getAttribute('data-uide-records-url');
        if (!url)
            return Promise.resolve();
        // Read from the app's own endpoint (docs/89): its answer, or the list under one of its names; each
        // item is a record by its id (`_id` as a MongoDB app writes it), and the endpoint decides who may change it.
        if (list.getAttribute('data-uide-from') === 'app') {
            const at = list.getAttribute('data-uide-at');
            return call(url, 'GET').then((res) => {
                const answer = (at ? res.data[at] : res.data);
                const items = res.ok && Array.isArray(answer) ? answer.filter((x) => x && typeof x === 'object') : [];
                drawRecords(list, items.map((values) => ({ id: String(values._id ?? values.id ?? ''), mine: true, values })));
            });
        }
        return call(url + (list.hasAttribute('data-uide-mine') ? '?mine=1' : ''), 'GET').then((res) => drawRecords(list, res.ok && Array.isArray(res.data.records) ? res.data.records : []));
    };
    const reloadApp = () => Promise.all([].slice.call(doc.querySelectorAll('[data-uide-from="app"]')).filter((l) => !editing(l)).map(loadRecords));
    /** What an endpoint answered when it said no, in the words it used. */
    const refusal = (data) => {
        const errors = data.errors;
        const said = [data.message, data.msg, data.error, Array.isArray(errors) && errors[0] ? errors[0].msg : undefined].filter((x) => typeof x === 'string' && x)[0];
        return said || 'That didn’t work. Please try again.';
    };
    const reloadFor = (url) => Promise.all([].slice.call(doc.querySelectorAll('[data-uide-records-url]')).filter((l) => !editing(l) && absolute(l.getAttribute('data-uide-records-url') || '') === absolute(url)).map(loadRecords));
    const statusOf = (form) => {
        let s = form.querySelector(':scope > .uide-form-status');
        if (!s) {
            s = doc.createElement('p');
            s.className = 'uide-form-status';
            s.setAttribute('role', 'status');
            form.appendChild(s);
        }
        return s;
    };
    /** A button calling one of the app's endpoints (docs/89), with its record's id in the path. */
    const pressCall = (button) => {
        if (button.hasAttribute('data-uide-busy'))
            return;
        const copy = button.closest('[data-uide-rec]');
        const method = button.getAttribute('data-uide-call') || 'POST';
        const url = (button.getAttribute('data-uide-call-url') || '').replace(/:[A-Za-z][A-Za-z0-9_]*/, () => encodeURIComponent(copy ? copy.getAttribute('data-uide-rec') || '' : ''));
        if (!url)
            return;
        if (method === 'DELETE' && win.confirm && !win.confirm('Delete this?'))
            return;
        button.setAttribute('data-uide-busy', '');
        void call(url, method).then((res) => {
            button.removeAttribute('data-uide-busy');
            const holder = button.parentElement || button;
            const status = holder.querySelector(':scope > .uide-form-status');
            if (status)
                status.remove();
            if (!res.ok) {
                const s = doc.createElement('p');
                s.className = 'uide-form-status';
                s.setAttribute('role', 'status');
                s.textContent = refusal(res.data);
                holder.appendChild(s);
                return;
            }
            return reloadApp();
        });
    };
    const onRecordAction = (button) => {
        const copy = button.closest('[data-uide-rec]');
        const list = copy ? copy.closest('[data-uide-records-url]') : null;
        if (!copy || !list)
            return;
        const url = list.getAttribute('data-uide-records-url') || '';
        const id = copy.getAttribute('data-uide-rec') || '';
        if (button.getAttribute('data-uide-record-action') === 'delete') {
            if (win.confirm && !win.confirm('Delete this?'))
                return;
            void call(url + '/' + encodeURIComponent(id), 'DELETE').then(() => reloadFor(url));
            return;
        }
        // Edit: the page's form for this collection takes the record's values; saving it changes the record.
        const form = [].slice.call(doc.querySelectorAll('form[data-uide-form="record"]')).filter((f) => absolute(f.getAttribute('action') || '') === absolute(url))[0];
        const rec = (records.get(list.getAttribute('data-uide-list') || '') || []).filter((r) => r.id === id)[0];
        if (!form || !rec)
            return;
        [].forEach.call(form.querySelectorAll('[data-uide-field]'), (input) => {
            const v = rec.values[input.getAttribute('data-uide-field') || ''];
            const type = input.getAttribute('type');
            // As the input shows it: yes/no as its choices, a date for a day picker, a time added for a day-and-time one.
            let text = v === null || v === undefined ? '' : typeof v === 'boolean' ? (v ? 'Yes' : 'No') : String(v);
            if (type === 'date')
                text = text.slice(0, 10);
            else if (type === 'datetime-local' && /^\d{4}-\d{2}-\d{2}$/.test(text))
                text += 'T00:00';
            else if (type === 'datetime-local')
                text = text.slice(0, 16);
            input.value = text;
        });
        form.setAttribute('data-uide-editing', id);
        statusOf(form).textContent = 'Editing: change it and send to save.';
        if (form.scrollIntoView)
            form.scrollIntoView({ block: 'center' });
    };
    /** A form sending to one of the app's endpoints (docs/89): its fields as JSON, numbers as numbers, empty ones left out. */
    const sendCall = (form) => {
        if (form.hasAttribute('data-uide-busy'))
            return;
        const body = {};
        [].forEach.call(form.querySelectorAll('[data-uide-field]'), (input) => {
            const v = input.value;
            if (v.trim() === '')
                return;
            body[input.getAttribute('data-uide-field') || ''] = input.getAttribute('type') === 'number' && !isNaN(Number(v)) ? Number(v) : v;
        });
        const status = statusOf(form);
        form.setAttribute('data-uide-busy', '');
        void call(form.getAttribute('action') || '', form.getAttribute('data-uide-call') || 'POST', body, true).then((res) => {
            form.removeAttribute('data-uide-busy');
            if (!res.ok) {
                status.textContent = refusal(res.data);
                return;
            }
            form.reset();
            status.textContent = form.getAttribute('data-uide-success') || 'Sent.';
            return reloadApp();
        });
    };
    const onSubmit = (e) => {
        const form = e.target;
        if (form && form.matches && form.matches('form[data-uide-call]') && !editing(form)) {
            e.preventDefault();
            sendCall(form);
            return;
        }
        if (!form || !form.matches || !form.matches('form[data-uide-form="record"]') || editing(form))
            return;
        e.preventDefault();
        if (form.hasAttribute('data-uide-busy'))
            return;
        const url = form.getAttribute('action') || '';
        const values = {};
        [].forEach.call(form.querySelectorAll('[data-uide-field]'), (input) => {
            values[input.getAttribute('data-uide-field') || ''] = input.value;
        });
        const id = form.getAttribute('data-uide-editing');
        const status = statusOf(form);
        form.setAttribute('data-uide-busy', '');
        void call(id ? url + '/' + encodeURIComponent(id) : url, id ? 'PUT' : 'POST', { values }).then((res) => {
            form.removeAttribute('data-uide-busy');
            if (!res.ok) {
                status.textContent = typeof res.data.message === 'string' ? res.data.message : 'That didn’t work. Please try again.';
                return;
            }
            form.reset();
            form.removeAttribute('data-uide-editing');
            status.textContent = id ? 'Saved.' : form.getAttribute('data-uide-success') || 'Added.';
            return reloadFor(url);
        });
    };
    const onInput = (e) => {
        const el = e.target;
        if (!el || !el.closest || !el.matches('[data-uide-search]'))
            return;
        const controls = el.closest('[data-uide-list-for]');
        if (controls && !editing(controls))
            restart(controls.getAttribute('data-uide-list-for') || '');
    };
    const onClick = (e) => {
        const el = e.target;
        if (!el || !el.closest || editing(el))
            return;
        const callButton = el.closest('button[data-uide-call]');
        if (callButton) {
            e.preventDefault();
            pressCall(callButton);
            return;
        }
        const recordButton = el.closest('[data-uide-record-action]');
        if (recordButton) {
            e.preventDefault();
            onRecordAction(recordButton);
            return;
        }
        const moreButton = el.closest('[data-uide-more]');
        if (moreButton) {
            e.preventDefault();
            const key = moreButton.getAttribute('data-uide-more') || '';
            const list = byKey('data-uide-list', key);
            if (!list)
                return;
            const page = Number(list.getAttribute('data-uide-page')) || 0;
            list.setAttribute('data-uide-shown', String((Number(list.getAttribute('data-uide-shown')) || page) + page));
            update(key);
            return;
        }
        const chip = el.closest('[data-uide-chip]');
        const controls = chip ? chip.closest('[data-uide-list-for]') : null;
        if (!chip || !controls)
            return;
        e.preventDefault();
        [].forEach.call(controls.querySelectorAll('[data-uide-chip]'), (b) => b.setAttribute('aria-pressed', b === chip ? 'true' : 'false'));
        restart(controls.getAttribute('data-uide-list-for') || '');
    };
    const onChange = (e) => {
        const el = e.target;
        if (!el || !el.closest || !el.matches('[data-uide-sort]'))
            return;
        const controls = el.closest('[data-uide-list-for]');
        if (!controls || editing(controls))
            return;
        const key = controls.getAttribute('data-uide-list-for') || '';
        order(key);
        restart(key);
    };
    doc.addEventListener('input', onInput);
    doc.addEventListener('click', onClick);
    doc.addEventListener('change', onChange);
    doc.addEventListener('submit', onSubmit);
    [].forEach.call(doc.querySelectorAll('[data-uide-records-url]'), (list) => {
        if (!editing(list))
            void loadRecords(list);
    });
    // Paged lists start at their first page (without the script, every record shows); an order the
    // browser kept from an earlier visit is applied.
    [].forEach.call(doc.querySelectorAll('[data-uide-list-for] [data-uide-sort]'), (choice) => {
        const controls = choice.closest('[data-uide-list-for]');
        if (controls && !editing(controls) && choice.selectedIndex > 0)
            order(controls.getAttribute('data-uide-list-for') || '');
    });
    [].forEach.call(doc.querySelectorAll('[data-uide-list]'), (list) => {
        const key = list.getAttribute('data-uide-list') || '';
        const counted = !!byKey('data-uide-list-for', key)?.querySelector('[data-uide-list-count]');
        if (!editing(list) && (list.hasAttribute('data-uide-page') || counted || list.querySelector(':scope > [data-uide-when]')))
            update(key);
    });
    return () => {
        doc.removeEventListener('input', onInput);
        doc.removeEventListener('click', onClick);
        doc.removeEventListener('change', onChange);
        doc.removeEventListener('submit', onSubmit);
    };
})(document)
}
