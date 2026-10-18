"""Run against `ng serve --port 4256`; requires Python Playwright + Chromium.
All API requests are intercepted with synthetic fixtures; no live service is used.
"""
import json
import os
from playwright.sync_api import sync_playwright, expect

ORIGIN = os.environ.get('SPLITEASY_TEST_ORIGIN', 'http://127.0.0.1:4256')
ALICE = dict(id='alice', displayName='Alice', email='alice@example.test')
BOB = dict(id='bob', displayName='Bob', email='bob@example.test')
GROUP = dict(id='g1', name='Keyboard trip', type='TRIP', members=[ALICE, BOB],
             createdBy=ALICE, simplifyDebts=False, createdAt='2026-10-17T12:00:00Z')
EXPENSE = dict(id='e1', groupId='g1', description='Lunch', amountCents=2000,
               splitType='EQUAL', category='FOOD_DRINK', spentOn='2026-10-17',
               paidBy=ALICE, participants=[dict(user=ALICE, shareCents=1000),
                                         dict(user=BOB, shareCents=1000)],
               participantCount=2, viewerDeltaCents=1000, createdAt='2026-10-17T12:00:00Z')
DASHBOARD = dict(totalNetCents=1000, owedCents=1000, owedPeopleCount=1, oweCents=0,
                 owePeopleCount=0, groupCount=1, people=[dict(user=BOB, netCents=1000)],
                 groups=[dict(id='g1', name=GROUP['name'], type='TRIP', memberCount=2,
                              totalSpentCents=2000, youAreOwedCents=1000, youOweCents=0,
                              netCents=1000, simplifyDebts=False)],
                 settlements=[dict(groupId='g1', groupName=GROUP['name'],
                                   counterparty=BOB, netCents=1000)], activity=[])


def api(route):
    path = route.request.url.split('/api/')[1]
    responses = {'dashboard': DASHBOARD, 'groups/g1': GROUP,
                 'groups/g1/expenses': [EXPENSE], 'groups/g1/expenses/e1': EXPENSE,
                 'groups/g1/balances': dict(groupId='g1', balances=[dict(user=ALICE, netCents=1000), dict(user=BOB, netCents=-1000)]),
                 'groups/g1/balances/mine': [dict(user=BOB, netCents=1000)],
                 'groups/g1/debt-simplification': dict(groupId='g1', transactions=[])}
    assert route.request.method == 'GET' and path in responses, (route.request.method, path)
    route.fulfill(status=200, content_type='application/json', body=json.dumps(responses[path]))


def inside(page):
    assert page.evaluate("!!document.activeElement?.closest('[role=dialog]')"), page.evaluate('document.activeElement.outerHTML')


def cycle(page):
    # Several full cycles, including controls revealed by async/content changes.
    count = page.get_by_role('dialog').locator('button:visible, input:visible, select:visible, a:visible').count()
    for key in ['Tab', 'Shift+Tab']:
        for _ in range(count + 3):
            page.keyboard.press(key)
            inside(page)


def check(page, launcher, name, change=None):
    handle = launcher.element_handle()
    launcher.focus()
    expect(launcher).to_be_focused()
    launcher.press('Enter')
    dialog = page.get_by_role('dialog', name=name, exact=True)
    expect(dialog).to_be_visible()
    expect(dialog.get_by_role('heading', name=name, exact=True)).to_be_focused()
    assert page.locator('.app-frame').evaluate('(el) => el.inert')
    page.locator('.skip-link').evaluate('(el) => el.focus()')
    inside(page)
    ax = page.context.new_cdp_session(page)
    tree = ax.send('Accessibility.getFullAXTree')['nodes']
    assert not any(not node.get('ignored') and node.get('name', {}).get('value') == 'Skip to content' for node in tree)
    assert any(node.get('role', {}).get('value') == 'dialog' and node.get('name', {}).get('value') == name for node in tree)
    ax.detach()
    bounds = dialog.bounding_box()
    assert bounds and bounds['x'] >= 0 and bounds['y'] >= 0
    assert bounds['x'] + bounds['width'] <= page.viewport_size['width']
    assert bounds['y'] + bounds['height'] <= page.viewport_size['height']
    if name == 'New expense' and page.viewport_size['height'] < 600:
        assert dialog.evaluate('(el) => el.scrollHeight > el.clientHeight')
        dialog.evaluate('(el) => el.scrollTop = el.scrollHeight')
        assert dialog.evaluate('(el) => el.scrollTop > 0')
        scroll = page.evaluate('window.scrollY')
        page.mouse.wheel(0, 500)
        assert page.evaluate('window.scrollY') == scroll
    assert page.evaluate('document.body.style.overflow') == 'hidden'
    # Native inert rejects even programmatic background focus.
    handle.evaluate('(el) => el.focus()')
    inside(page)
    cycle(page)
    if change:
        change(dialog)
        inside(page)
        cycle(page)
    page.keyboard.press('Escape')
    expect(dialog).to_have_count(0)
    expect(launcher).to_be_focused()
    assert not page.locator('.app-frame').evaluate('(el) => el.inert')
    assert page.evaluate('document.body.style.overflow') == ''
    launcher.press('Enter')
    page.get_by_role('dialog', name=name, exact=True).get_by_role('button', name='Close', exact=True).click()
    expect(launcher).to_be_focused()
    print('PASS', name, flush=True)


def expense_splits(dialog):
    for name in ['Unequal', 'Percent', 'Equally']:
        control = dialog.get_by_role('button', name=name, exact=True)
        control.press('Enter')
        expect(control).to_be_focused()
        inside(dialog.page)
        cycle(dialog.page)


def settle_steps(dialog):
    row = dialog.get_by_role('button', name='Bob owes you')
    row.focus()
    row.press('Enter')
    expect(dialog.get_by_role('heading')).to_be_focused()
    inside(dialog.page)
    cycle(dialog.page)
    dialog.get_by_role('button', name='Back', exact=True).press('Enter')
    expect(dialog.get_by_role('heading')).to_be_focused()
    inside(dialog.page)
    row.press('Enter')


with sync_playwright() as p:
    browser = p.chromium.launch(executable_path='/usr/bin/chromium', headless=True, args=['--no-sandbox'])
    for width, height in [(1440, 900), (320, 568), (844, 390)]:
        context = browser.new_context(viewport=dict(width=width, height=height))
        context.add_init_script("localStorage.setItem('spliteasy.accessToken','synthetic-token'); localStorage.setItem('spliteasy.user'," + json.dumps(json.dumps(ALICE)) + ');')
        page = context.new_page()
        errors = []
        page.on('pageerror', lambda error: (errors.append(str(error)), print('PAGE ERROR', str(error), flush=True)))
        page.on('console', lambda message: errors.append(message.text) if message.type == 'error' else None)
        page.route('**/api/**', api)
        page.goto(ORIGIN + '/dashboard')
        print('VIEWPORT', width, height, flush=True)
        check(page, page.get_by_role('button', name='Appearance', exact=True).filter(visible=True), 'Make it yours.')
        check(page, page.get_by_role('button', name='New group', exact=True), 'New group')
        page.goto(ORIGIN + '/groups/g1')
        check(page, page.get_by_role('button', name='Add expense', exact=True), 'New expense', expense_splits)
        check(page, page.get_by_role('button', name='Settle up', exact=True).last, 'Settle up',
              settle_steps)
        page.get_by_role('button', name='Lunch').click()
        check(page, page.get_by_role('button', name='Edit', exact=True), 'Edit expense')
        page.get_by_role('link', name='Group settings').click()
        expect(page.get_by_role('heading', name='Members', exact=True)).to_be_visible()
        check(page, page.get_by_role('button', name='Add member', exact=True), 'Add member')
        assert not errors, errors
        context.close()
    browser.close()
print('PASS all dialog checks')
