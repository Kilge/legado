const RELAY_PATH_PATTERN = /^\/d\/([A-Za-z0-9_-]{16,128})(?:\/|$)/
const RELAY_TOKEN_KEY = 'legado_relay_token'
const RELAY_TOKEN_PATTERN = /^[A-Za-z0-9_-]{24,512}$/

export type RelayBootstrap = {
  deviceId: string
  httpBase: string
  websocketBase: string
}

const parseRelayUrl = (input: string | URL): RelayBootstrap | null => {
  try {
    const url = new URL(input)
    const match = RELAY_PATH_PATTERN.exec(url.pathname)
    if (!match) return null

    const basePath = `/d/${match[1]}/`
    const httpUrl = new URL(basePath, url.origin)
    const websocketUrl = new URL(basePath, url.origin)
    websocketUrl.protocol = url.protocol === 'https:' ? 'wss:' : 'ws:'

    return {
      deviceId: match[1],
      httpBase: httpUrl.toString(),
      websocketBase: websocketUrl.toString(),
    }
  } catch {
    return null
  }
}

const readTokenFromHash = (): string | null => {
  const hash = location.hash
  if (!hash) return null

  const queryIndex = hash.indexOf('?')
  const routePart = queryIndex >= 0 ? hash.slice(0, queryIndex) : '#/'
  const parameterText =
    queryIndex >= 0 ? hash.slice(queryIndex + 1) : hash.slice(1)
  const parameters = new URLSearchParams(parameterText)
  const token = parameters.get('relay_token') || parameters.get('token')
  if (!token) return null

  parameters.delete('relay_token')
  parameters.delete('token')
  const remaining = parameters.toString()
  const nextHash = `${routePart || '#/'}${remaining ? `?${remaining}` : ''}`
  history.replaceState(
    null,
    '',
    `${location.pathname}${location.search}${nextHash}`,
  )
  return RELAY_TOKEN_PATTERN.test(token) ? token : null
}

const storeRelayToken = (token: string) => {
  try {
    sessionStorage.setItem(RELAY_TOKEN_KEY, token)
  } catch {
    // Strict privacy modes may disable storage. Failing closed is safer than
    // copying the capability into persistent storage.
  }
}

const currentRelay = parseRelayUrl(location.href)
const fragmentToken = currentRelay ? readTokenFromHash() : null
if (fragmentToken) storeRelayToken(fragmentToken)

export const getRelayBootstrap = () => currentRelay

export const getRelayBootstrapForUrl = (input: string | URL) =>
  parseRelayUrl(input)

export const getRelayToken = (): string | null => {
  try {
    const token = sessionStorage.getItem(RELAY_TOKEN_KEY)
    return token && RELAY_TOKEN_PATTERN.test(token) ? token : null
  } catch {
    return null
  }
}

export const isCurrentRelayRequest = (input: string | URL): boolean => {
  if (!currentRelay) return false
  const candidate = parseRelayUrl(input)
  return (
    candidate?.deviceId === currentRelay.deviceId &&
    candidate.httpBase === currentRelay.httpBase
  )
}

export const getRelayAuthorization = (input: string | URL): string | null => {
  if (!isCurrentRelayRequest(input)) return null
  const token = getRelayToken()
  return token ? `Bearer ${token}` : null
}

export const initializeRelaySession = async (): Promise<void> => {
  if (!currentRelay) return
  const token = getRelayToken()
  if (!token) return

  const response = await fetch(new URL('_session', currentRelay.httpBase), {
    method: 'POST',
    credentials: 'same-origin',
    cache: 'no-store',
    referrerPolicy: 'no-referrer',
    headers: {
      'Content-Type': 'application/json',
      Authorization: `Bearer ${token}`,
    },
    body: JSON.stringify({ token }),
  })

  if (!response.ok) {
    throw new Error(`Relay session exchange failed: ${response.status}`)
  }

  try {
    sessionStorage.removeItem(RELAY_TOKEN_KEY)
  } catch {
    // The HttpOnly session cookie is authoritative after a successful exchange.
  }
}
