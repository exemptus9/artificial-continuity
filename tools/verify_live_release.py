"""Compare every published app asset with the checked-out release. No credentials."""
from concurrent.futures import ThreadPoolExecutor
from hashlib import sha256
from pathlib import Path
from urllib.parse import urljoin, urlparse, quote
from urllib.request import Request, urlopen
import json
import os
import time


def main():
    base = os.environ['LIVE_URL'].rstrip('/') + '/'
    if urlparse(base).scheme != 'https':
        raise ValueError('Use the HTTPS deployment URL.')
    root = Path(__file__).resolve().parents[1] / 'site'
    token = quote(os.environ.get('GITHUB_SHA', 'release-check'), safe='')
    expected = {p.relative_to(root).as_posix(): sha256(p.read_bytes()).hexdigest()
                for p in root.rglob('*') if p.is_file() and not p.name.startswith('.')}
    pending = dict(expected)
    verified = {}
    deadline = time.monotonic() + 240
    def check(item):
        name, digest = item
        request = Request(urljoin(base, name) + '?verify=' + token,
                          headers={'Cache-Control': 'no-cache', 'User-Agent': 'Continuity-release-check'})
        try:
            with urlopen(request, timeout=20) as response:
                actual = sha256(response.read()).hexdigest()
            return name, actual == digest, actual
        except Exception as exc:
            return name, False, type(exc).__name__
    while pending and time.monotonic() < deadline:
        with ThreadPoolExecutor(max_workers=8) as pool:
            for name, ok, actual in pool.map(check, list(pending.items())):
                if ok:
                    verified[name] = actual
                    del pending[name]
        if pending:
            time.sleep(5)
    out = Path('test-artifacts'); out.mkdir(exist_ok=True)
    receipt = {'url': base, 'commit': os.environ.get('GITHUB_SHA'), 'version': '0.20.1',
               'verifiedAssets': verified, 'failedAssets': list(pending),
               'allPublishedBytesMatch': not pending}
    (out / 'live-assets.json').write_text(json.dumps(receipt, indent=2) + '\n')
    if pending:
        raise RuntimeError('Published assets did not match: ' + ', '.join(pending))
    print(f'LIVE VERIFIED: {len(verified)} assets match the release SHA-256 values.')

if __name__ == '__main__':
    main()
