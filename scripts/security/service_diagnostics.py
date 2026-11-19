"""Publish structural failure evidence, never raw service logs or request data."""
import json
from pathlib import Path
import re

LOG_NAMES = ('runtime', 'compose', 'gateway', 'user-service', 'tenant-service',
             'customer-service', 'billing-service', 'ops-service', 'realtime-service', 'ai')
EXCEPTION = re.compile(r'(?:Caused by:\s*|^|\s)((?:[a-zA-Z_$][\w$]*\.)+[\w$]*(?:Exception|Error))\b')
FRAME = re.compile(r'^\s*at ([a-zA-Z_$][\w$./<>]*\([A-Za-z0-9_$.-]+(?::[0-9]+)?\))\s*$')
# Emit fixed categories rather than database messages (which can contain input values).
DATABASE_REASONS = (
    ('permission denied', 'permission_denied'),
    ('violates row-level security', 'row_security_denied'),
    ('violates not-null constraint', 'not_null_violation'),
    ('violates unique constraint', 'unique_violation'),
    ('violates foreign key constraint', 'foreign_key_violation'),
    ('violates check constraint', 'check_violation'),
    ('does not exist', 'missing_database_object'),
    ('syntax error', 'sql_syntax_error'),
    ('out of range', 'numeric_out_of_range'),
    ('too long', 'value_too_long'),
    ('deadlock detected', 'deadlock'),
    ('current transaction is aborted', 'transaction_aborted'),
)


def structural_events(lines):
    events = []
    for number, line in enumerate(lines, 1):
        # Only exception classes and source locations can pass through. Do not
        # copy SQL, exception messages, environment, headers or response bodies.
        match = EXCEPTION.search(line)
        if match:
            events.append({'line': number, 'exception': match[1]})
        frame = FRAME.fullmatch(line.rstrip('\r\n'))
        if frame and ('io.supportflow.' in frame[1] or 'org.postgresql.' in frame[1]):
            events.append({'line': number, 'frame': frame[1]})
        if 'ERROR:' in line:
            for phrase, category in DATABASE_REASONS:
                if phrase in line:
                    events.append({'line': number, 'database_reason': category})
                    break
    return events[-180:]


def capture(logs: Path, destination: Path, scenario: str):
    """Read a fixed file allowlist; never recurse into credentials or fixtures."""
    report = {'scenario': scenario, 'format': 'structural-service-errors-v1', 'services': {}}
    for name in LOG_NAMES:
        source = logs / (name + '.log')
        if source.is_file() and not source.is_symlink():
            with source.open(encoding='utf-8', errors='replace') as stream:
                report['services'][name] = structural_events(stream)
    destination.parent.mkdir(parents=True, exist_ok=True)
    destination.write_text(json.dumps(report, indent=2) + '\n', encoding='utf-8')
    print('Structural service diagnostics: ' + str(destination), flush=True)
