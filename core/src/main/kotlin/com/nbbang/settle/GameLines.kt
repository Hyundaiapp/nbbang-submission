// C-18 게임 결과 카드 문구. 기획서: "재미있는 문구로 표시, 게임별 5종 이상".
package com.nbbang.settle

import com.nbbang.domain.Lines
import kotlin.math.abs

object GameLines {
    // {who}는 걸린 사람, {deal}은 "12,000원을 혼자 다 내요"(독박) / "6,000원을 몰아서 내요"(몰아주기). 문장 끝에 마침표를 붙인다.
    // 걸린 사람이 손해 보는 쪽이라 "운이 좋았어요" 같은 칭찬 말투는 쓰지 않는다.
    private val lines = mapOf(
        "사다리타기" to listOf(
            "사다리를 타고 내려가 보니 {who} 님! {deal}.",
            "사다리가 {who} 님을 골랐어요. {deal}.",
            "오늘의 사다리 주인공은 {who} 님, {deal}.",
            "꼬불꼬불 사다리 끝에 {who} 님이 있었어요. {deal}.",
            "사다리는 오늘 {who} 님 편이 아니었네요. {deal}.",
        ),
        "야바위" to listOf(
            "공은 {who} 님 컵 안에 있었어요. {deal}.",
            "섞고 또 섞어도 공은 {who} 님에게 갔어요. {deal}.",
            "컵을 열어 보니 {who} 님! {deal}.",
            "오늘 컵 돌리기에 걸린 사람은 {who} 님, {deal}.",
            "공이 {who} 님을 따라갔어요. {deal}.",
        ),
        "카드 뽑기" to listOf(
            "가장 높은 카드는 {who} 님 손에! {deal}.",
            "{who} 님이 제일 높은 숫자를 뽑았어요. {deal}.",
            "카드를 뒤집어 보니 {who} 님이 제일 높아요. {deal}.",
            "높은 카드가 오늘은 {who} 님 발목을 잡았네요. {deal}.",
            "카드의 신이 {who} 님을 골랐어요. {deal}.",
        ),
        "주사위 굴리기" to listOf(
            "가장 큰 눈은 {who} 님! {deal}.",
            "주사위의 선택은 {who} 님이에요. {deal}.",
            "굴려 보니 {who} 님 눈이 제일 커요. {deal}.",
            "주사위는 거짓말을 안 해요. {who} 님, {deal}.",
            "{who} 님 주사위가 가장 높이 나왔어요. {deal}.",
        ),
    )

    /** "12,000원을 혼자 다 내요" / "6,000원을 몰아서 내요" */
    fun deal(mode: GameMode, won: Int): String =
        if (mode == GameMode.ALL_IN) "${Lines.won(won)}을 혼자 다 내요" else "${Lines.won(won)}을 몰아서 내요"

    /** [game]은 GameKind.label("주사위 굴리기" 등). 모르는 이름이면 주사위 문구를 쓴다. [pick]으로 다섯 중 하나를 고른다. */
    fun line(game: String, mode: GameMode, who: String, won: Int, pick: Int): String {
        val set = lines[game] ?: lines.entries.firstOrNull { game.isNotBlank() && it.key.startsWith(game) }?.value ?: lines.getValue("주사위 굴리기")
        return set[abs(pick % set.size)].replace("{who}", who).replace("{deal}", deal(mode, won))
    }

    fun templates(game: String): List<String> = lines[game].orEmpty()
}
