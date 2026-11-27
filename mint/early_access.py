"""Public invitation signup and private owner roster on the archive's persistent volume."""
from contextlib import closing
from html import escape
from pathlib import Path
import re
import sqlite3
from urllib.parse import parse_qs

FORM = '''<h2>Get an early-access invitation</h2><p>Leave your email if you’d like to hear when testing opens.</p>
<form method="post" action="/early-access"><label for="email">Email address</label><div class="fields"><input type="email" id="email" name="email" maxlength="254" autocomplete="email" required></div>
<label class="consent"><input type="checkbox" name="invite" value="yes" required> Email me about testing Total Recall.</label><button type="submit">Request an invitation</button></form>'''
SAVED = '''<div class="success" role="status"><h2>Request received.</h2><p>Thanks for your interest. We'll use the address you entered for Total Recall testing invitations.</p></div>'''
EMAIL = re.compile(r"[^\s@<>\x00-\x1f]{1,64}@[a-zA-Z0-9-]+(?:\.[a-zA-Z0-9-]+)+")
def page(assets,content=FORM):
    return (assets/"early-access.html").read_text().replace("FORM",content).encode()

def register(raw,root):
    try:
        fields=parse_qs(raw.decode("ascii"),max_num_fields=3)
        email,=fields.get("email",[])
        email=email.strip().lower()
    except (UnicodeError,ValueError):
        return False
    if fields.get("invite")!=["yes"] or len(email)>254 or not EMAIL.fullmatch(email):
        return False
    with closing(connect(root)) as db, db:
        db.execute("INSERT OR IGNORE INTO invites(email) VALUES (?)",(email,))
    return True

def connect(root):
    db=sqlite3.connect(Path(root)/"early-access.sqlite3",timeout=8)
    db.execute("CREATE TABLE IF NOT EXISTS invites(email TEXT PRIMARY KEY, joined TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP)")
    return db

def roster(root):
    with closing(connect(root)) as db:
        return [{"email":r[0],"joined":r[1]} for r in db.execute("SELECT email,joined FROM invites ORDER BY joined DESC,email")]


def owner_page(assets, root):
    entries=roster(root)
    rows="".join("<tr><td>"+escape(item["email"])+"</td><td>"+escape(item["joined"])+"</td></tr>" for item in entries)
    content=("<h2>Invitation requests</h2><p>"+str(len(entries))+" addresses on the early-access list.</p>"
             '<div style="overflow:auto"><table><thead><tr><th>Email address</th><th>Joined (UTC)</th></tr></thead><tbody>'
             +rows+"</tbody></table></div>")
    return page(assets,content)
