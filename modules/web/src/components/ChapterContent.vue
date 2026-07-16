<template>
  <div class="title" data-chapterpos="0" ref="titleRef">{{ title }}</div>
  <div
    v-for="(para, index) in contents"
    :key="index"
    ref="paragraphRef"
    :data-chapterpos="chapterPos[index]"
  >
    <img
      class="full"
      v-if="!relayMode && /^\s*<img[^>]*src[^>]+>$/.test(String(para))"
      :src="getImageSrc(para)"
      @error.once="proxyImage"
      loading="lazy"
    />
    <p
      v-else
      :style="{ fontFamily, fontSize }"
      v-html="sanitizeContent(para)"
      @click="handleContentClick"
      @error.capture="handleImgLoadError"
    />
  </div>
  <Teleport to="body">
    <aside
      v-if="browserPanel"
      class="paragraph-browser-panel"
      :class="{ fullscreen: browserPanelFullscreen }"
    >
      <header>
        <strong>{{ browserPanel.title || '段评' }}</strong>
        <div>
          <button type="button" @click="browserPanelFullscreen = !browserPanelFullscreen">
            {{ browserPanelFullscreen ? '缩小' : '全屏' }}
          </button>
          <button type="button" @click="browserPanel = null">关闭</button>
        </div>
      </header>
      <div v-if="browserPanel.html" class="paragraph-browser-html" v-html="safeBrowserHtml" />
      <iframe
        v-else-if="browserPanel.url"
        :src="browserPanel.url"
        sandbox="allow-forms allow-popups allow-scripts"
        referrerpolicy="no-referrer"
      />
      <div v-else class="paragraph-browser-empty">没有可显示的段评内容</div>
    </aside>
  </Teleport>
</template>

<script setup lang="ts">
import { isLegadoUrl, lazyRegex } from '@/utils/utils'
import API from '@api'
import jump from '@/plugins/jump'
import type { webReadConfig } from '@/web'
import DOMPurify from 'dompurify'
import { getRelayBootstrap } from '@/api/relay'
import type { ParagraphBrowserResult } from '@/api/api'

const store = useBookStore()
const readWidth = computed(() => store.config.readWidth)
const lineImgWidth = computed(() => store.config.fontSize * 2)
const bookUrl = computed(() => store.readingBook.bookUrl)
const relayMode = getRelayBootstrap() !== null
const browserPanel = ref<ParagraphBrowserResult | null>(null)
const browserPanelFullscreen = ref(false)
const safeBrowserHtml = computed(() =>
  browserPanel.value?.html
    ? DOMPurify.sanitize(browserPanel.value.html, {
        USE_PROFILES: { html: true },
        FORBID_TAGS: ['script', 'iframe', 'object', 'embed', 'form'],
        FORBID_ATTR: ['style'],
      })
    : '',
)

const props = defineProps<{
  chapterIndex: number
  contents: Array<string>
  title: string
  spacing: webReadConfig['spacing']
  fontFamily: string
  fontSize: string
}>()

const imgPatternStr = '<img[^>]*src=[\'"]([^\'"]*(?:[\'"][^>]+\\})?)[\'"][^>]*>'
const imgPattern = lazyRegex(imgPatternStr)
const imgPatternAll = lazyRegex(imgPatternStr, 'g')
const imgDataUrlPattern = lazyRegex('data:image[^;]+;base64,[^,]{39,}')

const replaceImage = (content: string) => {
  return content.replace(imgPatternAll(), (match, src) => {
    const dataUrl = src.match(imgDataUrlPattern())
    if (dataUrl) {
      return dataUrl[0]
    }
    if (isLegadoUrl(src)) {
      const proxySrc = API.getProxyImageUrl(
        bookUrl.value,
        src,
        lineImgWidth.value,
      )
      return match.replace(src, proxySrc)
    }
    return match
  })
}

const sanitizeContent = (content: string) =>
  String(
    DOMPurify.sanitize(replaceImage(content), {
      ALLOWED_TAGS: relayMode
        ? [
            'br',
            'b',
            'strong',
            'i',
            'em',
            'u',
            's',
            'del',
            'span',
            'ruby',
            'rt',
            'rp',
          ]
        : [
            'br',
            'b',
            'strong',
            'i',
            'em',
            'u',
            's',
            'del',
            'span',
            'ruby',
            'rt',
            'rp',
            'img',
          ],
      ALLOWED_ATTR: relayMode
        ? ['class', 'data-legado-action', 'data-legado-count']
        : ['class', 'src', 'alt', 'title', 'width', 'height', 'loading'],
      ALLOW_DATA_ATTR: false,
    }),
  )

const handleContentClick = async (event: MouseEvent) => {
  const target = event.target instanceof Element
    ? event.target.closest<HTMLElement>('.legado-paragraph-bubble[data-legado-action]')
    : null
  const actionId = target?.dataset.legadoAction
  if (!relayMode || !actionId) return
  event.preventDefault()
  const response = await API.executeParagraphAction(
    actionId,
    bookUrl.value,
    props.chapterIndex,
  )
  if (response.data.isSuccess) {
    browserPanel.value = response.data.data
    browserPanelFullscreen.value = false
  } else {
    window.alert(response.data.errorMsg || '段评加载失败')
  }
}

const getImageSrc = (content: string) => {
  const src = content.match(imgPattern())![1] //reg tested in template
  const dataUrl = src.match(imgDataUrlPattern())
  if (dataUrl) {
    return dataUrl[0] //现成的base64图片，去掉阅读格式后缀
  }
  if (isLegadoUrl(src))
    return API.getProxyImageUrl(bookUrl.value, src, readWidth.value)
  return src
}
const proxyImage = (event: Event) => {
  /* 获取IMG标签原始的src
    <img src="/test" />
    假设location.href = http://example.com
    event.target.src 返回 http://example.com/test
    (event.target as HTMLImageElement)?.getAttribute("src")  返回/test
  */
  const src = (event.target as HTMLImageElement)?.getAttribute('src')
  if (src != null && src.length > 0) {
    ;(event.target as HTMLImageElement).src = API.getProxyImageUrl(
      bookUrl.value,
      src,
      readWidth.value,
    )
  }
}

/**
 * 处理传入的IMG标签错误事件，自动替换图片的代理链接
 */
const handleImgLoadError = (event: Event) => {
  const target = event.target
  if (target instanceof HTMLImageElement) {
    const srcUrl = target.getAttribute('src')
    console.log(
      '[ChapterContent]: IMG Load Error, replace src:',
      srcUrl,
      '=>',
      API.getProxyImageUrl(bookUrl.value, srcUrl ?? '', readWidth.value),
    )
    proxyImage(event)
  }
}

const calculateWordCount = (paragraph: string) => {
  //内嵌图片文字为1
  const imagePlaceHolder = ' '
  return paragraph.replace(imgPatternAll(), imagePlaceHolder).length
}
const chapterPos = computed(() => {
  let pos = -1
  return Array.from(props.contents, content => {
    pos += calculateWordCount(content) + 1 //计算上一段的换行符
    return pos
  })
})

const titleRef = ref<HTMLElement>()
const paragraphRef = ref<HTMLParagraphElement[]>()
const scrollToReadedLength = (length: number) => {
  if (length === 0) return
  const paragraphIndex = chapterPos.value.findIndex(
    wordCount => wordCount >= length,
  )
  if (paragraphIndex === -1) return
  nextTick(() => {
    jump(paragraphRef.value![paragraphIndex], {
      duration: 0,
    })
  })
}
defineExpose({
  scrollToReadedLength,
})
let intersectionObserver: IntersectionObserver | null = null
const emit = defineEmits(['readedLengthChange'])
onMounted(() => {
  intersectionObserver = new IntersectionObserver(
    entries => {
      for (const { target, isIntersecting } of entries) {
        if (isIntersecting) {
          emit(
            'readedLengthChange',
            props.chapterIndex,
            parseInt((target as HTMLElement).dataset.chapterpos as string),
          )
        }
      }
    },
    {
      rootMargin: `0px 0px -${window.innerHeight - 24}px 0px`,
    },
  )
  intersectionObserver.observe(titleRef.value!)
  paragraphRef.value!.forEach(element => {
    intersectionObserver!.observe(element)
  })
})

onUnmounted(() => {
  intersectionObserver?.disconnect()
  intersectionObserver = null
})
</script>

<style lang="scss" scoped>
.title {
  margin-bottom: 57px;
  font:
    24px / 32px PingFangSC-Regular,
    HelveticaNeue-Light,
    'Helvetica Neue Light',
    'Microsoft YaHei',
    sans-serif;
}

p {
  display: block;
  word-wrap: break-word;
  /*   word-break: break-all; */
  letter-spacing: calc(v-bind('props.spacing.letter') * 1em);
  line-height: calc(1 + v-bind('props.spacing.line'));
  margin: calc(v-bind('props.spacing.paragraph') * 1em) 0;

  :deep(img) {
    height: 1em;
  }
}

:global(.legado-paragraph-bubble) {
  display: inline-flex;
  min-width: 1.75em;
  height: 1.45em;
  align-items: center;
  justify-content: center;
  margin: 0 0.18em;
  padding: 0 0.48em;
  border-radius: 999px;
  background: color-mix(in srgb, currentColor 14%, transparent);
  font-size: 0.72em;
  line-height: 1;
  cursor: pointer;
  vertical-align: 0.08em;
}

.paragraph-browser-panel {
  position: fixed;
  z-index: 3000;
  top: 0;
  right: 0;
  width: min(520px, 94vw);
  height: 100dvh;
  display: flex;
  flex-direction: column;
  background: var(--el-bg-color, #fff);
  color: var(--el-text-color-primary, #202124);
  box-shadow: -12px 0 32px rgb(0 0 0 / 20%);
}

.paragraph-browser-panel.fullscreen {
  width: 100vw;
}

.paragraph-browser-panel header {
  min-height: 54px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 0 16px;
  border-bottom: 1px solid var(--el-border-color, #ddd);
}

.paragraph-browser-panel header button {
  margin-left: 8px;
}

.paragraph-browser-panel iframe,
.paragraph-browser-html {
  width: 100%;
  flex: 1;
  border: 0;
  overflow: auto;
}

.paragraph-browser-html,
.paragraph-browser-empty {
  box-sizing: border-box;
  padding: 16px;
}

.full {
  display: block;
  width: 100%;
}
</style>
