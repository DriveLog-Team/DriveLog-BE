const test = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const vm = require("node:vm");
const path = require("node:path");
const script = fs.readFileSync(path.join(__dirname, "../../main/resources/static/api/handoff.js"), "utf8");
const id = "12345678-1234-4321-8765-1234567890ab";
const code = "A".repeat(43);

function open(hash, agent = "Android", search = "") {
    const story = {hidden: true};
    const button = {disabled: true};
    const status = {};
    const form = {elements: {id: {}, code: {}}, querySelector: () => button};
    let clean;
    vm.runInNewContext(script, {
        location: {hash, search, pathname: "/api/handoffs/open", origin: "https://example.org"},
        history: {replaceState: (_, __, value) => { clean = value; }},
        document: {querySelector: s => ({"#story": story, form, "[role=status]": status}[s])},
        navigator: {userAgent: agent}, URLSearchParams
    });
    return {story, form, button, status, clean};
}

test("Android opens the named phone app and can fall back without consuming the ticket", () => {
    const hash = "#" + id + "." + code;
    const result = open(hash);
    assert.equal(result.clean, "/api/handoffs/open");
    assert.equal(result.story.hidden, false);
    assert.ok(result.story.href.startsWith("intent://story/" + id + "#Intent;scheme=drivelog;package=kr.drivelog.phone;S.ticket=" + code + ";"));
    const fallback = new URL(decodeURIComponent(result.story.href.match(/S\.browser_fallback_url=([^;]+)/)[1]));
    assert.equal(fallback.origin, "https://example.org");
    assert.equal(fallback.hash, hash);
    assert.equal(fallback.search, "?app=missing");
    const retry = open(fallback.hash, "Android", fallback.search);
    assert.equal(retry.form.elements.code.value, code);
    assert.equal(retry.button.disabled, false);
    assert.match(retry.status.textContent, /설치/);
});

test("iPhone can download and malformed tickets enable neither action", () => {
    const ios = open("#" + id + "." + code, "iPhone");
    assert.equal(ios.story.hidden, true);
    assert.equal(ios.button.disabled, false);
    for (const hash of ["", "#bad", "#" + id + "." + code + ";end"]) {
        const invalid = open(hash);
        assert.equal(invalid.button.disabled, true);
        assert.equal(invalid.story.hidden, true);
    }
});
