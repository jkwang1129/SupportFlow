"""Short-lived action permits; consumers must still authorize resources and deduplicate writes."""
import hmac

AUDIENCE = 'supportflow-agent-action:v1'
FIELDS = ('version','tenant_code','run_id','source_event_id','owner','fence','step_key','step_no',
          'operation','snapshot_id','arguments_sha256','expires_at_ms')


def sign(grant, secret):
    key = secret.encode() if isinstance(secret,str) else secret
    if len(key)<32 or set(grant)!=set(FIELDS): raise ValueError('Invalid action signing input')
    canonical='\n'.join((AUDIENCE, *(str(grant[field]) for field in FIELDS)))
    return {**grant,'signature':hmac.digest(key,canonical.encode(),'sha256').hex()}
