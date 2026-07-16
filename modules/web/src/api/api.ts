/** https://github.com/gedoor/legado/tree/master/app/src/main/java/io/legado/app/api */
/** https://github.com/gedoor/legado/tree/master/app/src/main/java/io/legado/app/web */

import type { webReadConfig } from '@/web'
import ajax from './axios'
import type {
  BaseBook,
  Book,
  BookChapter,
  BookProgress,
  SeachBook,
} from '@/book'
import type { Source } from '@/source'
import { getRelayAuthorization, getRelayBootstrap } from './relay'

export type LeagdoApiResponse<T> = {
  isSuccess: boolean
  errorMsg: string
  data: T
}

export let legado_http_entry_point = ''
export let legado_webSocket_entry_point = ''

let wsOnError: typeof WebSocket.prototype.onerror = () => {}
let wsOnMessage: typeof WebSocket.prototype.onmessage = () => {}
export const setWebsocketOnMessage = (callback: typeof wsOnMessage) =>
  (wsOnMessage = callback)
export const setWebsocketOnError = (callback: typeof wsOnError) => {
  //WebSocket.prototype.onerror = callback
  wsOnError = callback
}

export const setApiEntryPoint = (
  http_entry_point: string,
  webSocket_entry_point: string,
) => {
  legado_http_entry_point = new URL(http_entry_point).toString()
  legado_webSocket_entry_point = new URL(webSocket_entry_point).toString()
  ajax.defaults.baseURL = legado_http_entry_point
}

// 书架API
// Http
const getReadConfig = async (http_url = legado_http_entry_point) => {
  const { data } = await ajax.get<LeagdoApiResponse<string>>('getReadConfig', {
    baseURL: http_url.toString(),
    timeout: 3000,
  })
  if (data.isSuccess) {
    try {
      return JSON.parse(data.data) as webReadConfig
    } catch {}
  }
}
const saveReadConfig = (config: webReadConfig) =>
  ajax.post<LeagdoApiResponse<string>>('saveReadConfig', config)

/** @deprecated: 使用`API.saveBookProgressWithBeacon`以确保在页面或者直接关闭的情况下保存进度 */
const saveBookProgress = (bookProgress: BookProgress) =>
  ajax.post('saveBookProgress', bookProgress)

/**主要在直接关闭浏览器情况下可靠发送书籍进度 */
const saveBookProgressWithBeacon = (bookProgress: BookProgress) => {
  if (!bookProgress) return
  const url = new URL('saveBookProgress', legado_http_entry_point)
  const authorization = getRelayAuthorization(url)
  void fetch(url, {
    method: 'POST',
    body: JSON.stringify(bookProgress),
    keepalive: true,
    credentials: 'same-origin',
    headers: {
      'Content-Type': 'application/json',
      ...(authorization ? { Authorization: authorization } : {}),
    },
  })
}

const getBookShelf = () => ajax.get<LeagdoApiResponse<Book[]>>('getBookshelf')

const getChapterList = (/** @type {string} */ bookUrl: string) =>
  ajax.get<LeagdoApiResponse<BookChapter[]>>(
    'getChapterList?url=' + encodeURIComponent(bookUrl),
  )

const getBookContent = (
  /** @type {string} */ bookUrl: string,
  /** @type {number} */ chapterIndex: number,
) => {
  const path = getRelayBootstrap() ? 'getBookContentEx' : 'getBookContent'
  return ajax.get<LeagdoApiResponse<string>>(
    path + '?url=' +
      encodeURIComponent(bookUrl) +
      '&index=' +
      chapterIndex,
  )
}

export type ParagraphBrowserResult = {
  type: 'browser_panel'
  title: string
  url: string
  html?: string | null
  preloadJs?: string | null
  config?: string | null
}

const executeParagraphAction = (
  actionId: string,
  bookUrl: string,
  chapterIndex: number,
) =>
  ajax.post<LeagdoApiResponse<ParagraphBrowserResult>>('paragraph/action', {
    actionId,
    bookUrl,
    chapterIndex,
  })

// webSocket
const search = (
  searchKey: string,
  onReceive: (data: SeachBook[]) => void,
  onFinish: () => void,
) => {
  const httpUrl = new URL('searchBook', legado_http_entry_point)
  const relayAuthorization = getRelayAuthorization(httpUrl)
  if (getRelayBootstrap()) {
    void streamRelaySearch(
      httpUrl,
      relayAuthorization,
      searchKey,
      onReceive,
      onFinish,
    )
    return
  }
  const socket = new WebSocket(
    new URL('searchBook', legado_webSocket_entry_point),
  )
  socket.onerror = wsOnError

  socket.onopen = () => {
    socket.send(`{"key":"${searchKey}"}`)
  }
  socket.onmessage = event => {
    try {
      onReceive(JSON.parse(event.data))
      wsOnMessage?.call(socket, event)
    } catch {
      onFinish()
    }
  }

  socket.onclose = () => {
    onFinish()
  }
}

type RelaySearchEvent = {
  type: 'results' | 'finish' | 'error'
  books?: SeachBook[]
  message?: string
}

const streamRelaySearch = async (
  url: URL,
  authorization: string | null,
  searchKey: string,
  onReceive: (data: SeachBook[]) => void,
  onFinish: () => void,
) => {
  try {
    const response = await fetch(url, {
      method: 'POST',
      credentials: 'same-origin',
      headers: {
        'Content-Type': 'application/json',
        Accept: 'application/x-ndjson',
        ...(authorization ? { Authorization: authorization } : {}),
      },
      body: JSON.stringify({ key: searchKey }),
    })
    if (!response.ok || !response.body) throw new Error(`search_${response.status}`)
    const reader = response.body.getReader()
    const decoder = new TextDecoder()
    let pending = ''
    while (true) {
      const { done, value } = await reader.read()
      pending += decoder.decode(value, { stream: !done })
      const lines = pending.split('\n')
      pending = lines.pop() ?? ''
      for (const line of lines) {
        if (!line.trim()) continue
        const event = JSON.parse(line) as RelaySearchEvent
        if (event.type === 'results' && Array.isArray(event.books)) {
          onReceive(event.books)
        }
        if (event.type === 'error') throw new Error(event.message || 'search_failed')
      }
      if (done) break
    }
  } catch (error) {
    wsOnError?.call({} as WebSocket, new Event(String(error)))
  } finally {
    onFinish()
  }
}

const saveBook = (book: BaseBook) =>
  ajax.post<LeagdoApiResponse<string>>('saveBook', book)
const deleteBook = (book: BaseBook) =>
  ajax.post<LeagdoApiResponse<string>>('deleteBook', book)

const isBookSource = /bookSource/i.test(location.href)

// 源编辑API
// Http
const getSources = () =>
  isBookSource ? ajax.get('getBookSources') : ajax.get('getRssSources')

const saveSource = (data: Source) =>
  isBookSource
    ? ajax.post<LeagdoApiResponse<string>>('saveBookSource', data)
    : ajax.post<LeagdoApiResponse<string>>('saveRssSource', data)

const saveSources = (data: Source[]) =>
  isBookSource
    ? ajax.post<LeagdoApiResponse<Source[]>>('saveBookSources', data)
    : ajax.post<LeagdoApiResponse<Source[]>>('saveRssSources', data)

const deleteSource = (data: Source[]) =>
  isBookSource
    ? ajax.post<LeagdoApiResponse<string>>('deleteBookSources', data)
    : ajax.post<LeagdoApiResponse<string>>('deleteRssSources', data)

// webSocket
const debug = (
  /** @type {string} */ sourceUrl: string,
  /** @type {string} */ searchKey: string,
  /** @type {(data: string) => void} */ onReceive: (data: string) => void,
  /** @type {() => void} */ onFinish: () => void,
) => {
  const url = new URL(
    `${isBookSource ? 'bookSource' : 'rssSource'}Debug`,
    legado_webSocket_entry_point,
  )

  const socket = new WebSocket(url)
  socket.onerror = wsOnError
  socket.onopen = () => {
    socket.send(JSON.stringify({ tag: sourceUrl, key: searchKey }))
  }
  socket.onmessage = event => {
    onReceive(event.data)
    wsOnMessage?.call(socket, event)
  }

  socket.onclose = () => {
    onFinish()
  }
}

/**
 * 从阅读获取需要特定处理的书籍封面
 * @param {string} coverUrl
 */
const getProxyCoverUrl = (coverUrl: string) => {
  if (coverUrl.startsWith(legado_http_entry_point)) return coverUrl
  return new URL(
    'cover?path=' + encodeURIComponent(coverUrl),
    legado_http_entry_point,
  ).toString()
}

const getBookCoverUrl = (bookUrl: string) =>
  new URL(
    'getBookCover?url=' + encodeURIComponent(bookUrl),
    legado_http_entry_point,
  ).toString()
/**
 * 从阅读获取需要特定处理的图片
 * @param {string} bookUrl
 * @param {string} src
 * @param {number|`${number}`} width
 */
const getProxyImageUrl = (
  bookUrl: string,
  src: string,
  width: number | `${number}`,
) => {
  if (src.startsWith(legado_http_entry_point)) return src
  return new URL(
    'image?path=' +
      encodeURIComponent(src) +
      '&url=' +
      encodeURIComponent(bookUrl) +
      '&width=' +
      width,
    legado_http_entry_point,
  ).toString()
}

export default {
  getReadConfig,
  saveReadConfig,
  saveBookProgress,
  saveBookProgressWithBeacon,
  getBookShelf,
  getChapterList,
  getBookContent,
  executeParagraphAction,
  search,
  saveBook,
  deleteBook,

  getSources,
  saveSources,
  saveSource,
  deleteSource,
  debug,

  getProxyCoverUrl,
  getBookCoverUrl,
  getProxyImageUrl,
}
