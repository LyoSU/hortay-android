package dev.lyo.telread.data

/**
 * 🎯 LEARNING CHECKPOINT #1 — feed shaping rules
 *
 * Telegram-канали постять різнорідно:
 *   • Звичайні текстові пости.
 *   • Альбоми (media groups) — N окремих TdApi.Message з однаковим mediaAlbumId. У стрічку
 *     треба як одну картку, інакше бачимо 10 однакових записів підряд.
 *   • Реклама / спонсорські вставки (помічаємо за emoji «#ad», довжиною, posters of well-known
 *     ad-bots, або просто пропускаємо forwarded-пости?).
 *   • Дуже короткі реакції («🔥», «👏») — для twitter-feed читалки це шум.
 *
 * 🛠️ Ваше завдання — реалізувати [apply]:
 *   1. Згрупуйте пости одного альбому в один [TimelinePost] (склейте текст із непустих, додайте
 *      mediaCount = розмір групи, візьміть наймолодший id як ключ).
 *   2. Викиньте «шумові» пости: текст коротший за N символів І без медіа.
 *   3. Відсортуйте за date desc.
 *
 * Підказки:
 *   • mediaAlbumId зараз НЕ виноситься у [TimelinePost] — або додайте його туди, або
 *     передавайте сирі TdApi.Message сюди (тоді змініть і PostsRepository.toPost щоб мапити
 *     ПІСЛЯ фільтра). Який варіант чистіший — ваш вибір.
 *   • Поріг «короткого» поста — 12-15 символів типово працює добре. Налаштуйте під смак.
 *   • Не перевикористовуйте id первинного поста для групи — беріть min(id) всередині групи,
 *     щоб ключ LazyColumn був стабільним.
 *
 * Trade-offs to think about:
 *   • Агресивний фільтр = чистіша стрічка, але ризик викинути цікавий короткий допис.
 *   • М'який фільтр = більше шуму, але користувач бачить усе.
 *   • Логіку угруповання можна винести у TdClient (на event-рівні) або тут (на batch-рівні).
 *     Batch (тут) простіший для прототипу; event-level масштабується краще.
 */
object PostFilterStrategy {

    fun apply(raw: List<TimelinePost>): List<TimelinePost> {
        // TODO(you): implement grouping + filtering + sorting per docs above.
        // For now, return raw sorted by date desc — replace with your logic.
        return raw.sortedByDescending { it.date }
    }
}
