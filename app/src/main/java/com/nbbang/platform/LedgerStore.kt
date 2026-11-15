package com.nbbang.platform

import android.content.Context
import com.nbbang.domain.CarModel
import com.nbbang.domain.Charge
import com.nbbang.domain.Cost
import com.nbbang.domain.DRIVER_ID
import com.nbbang.domain.DrivingMix
import com.nbbang.domain.Extra
import com.nbbang.domain.ExtraKind
import com.nbbang.domain.GeoPoint
import com.nbbang.domain.Payee
import com.nbbang.domain.Place
import com.nbbang.domain.Power
import com.nbbang.domain.Prices
import com.nbbang.domain.Ride
import com.nbbang.domain.RideGame
import com.nbbang.domain.RideSettings
import com.nbbang.domain.Rider
import com.nbbang.domain.Seat
import com.nbbang.domain.Segment
import com.nbbang.domain.SplitRule
import com.nbbang.domain.TollPass
import com.nbbang.domain.TrackerSnapshot
import com.nbbang.domain.TripMode
import com.nbbang.domain.TripSetup
import com.nbbang.settle.CarpoolGroup
import com.nbbang.settle.ExpenseSource
import com.nbbang.settle.GameMode
import com.nbbang.settle.OfficialPrice
import java.time.LocalDate
import java.time.YearMonth
import org.json.JSONArray
import org.json.JSONObject

/**
 * 장부와 설정은 차 안에만 저장한다. 서버로 보내지 않는다.
 * 동승자에게는 정산 QR에 담긴 금액과 받을 곳만 보인다.
 */
class LedgerStore(context: Context) {
    private val prefs = context.getSharedPreferences("ride_ledger", Context.MODE_PRIVATE)

    var locationAsked: Boolean
        get() = prefs.getBoolean(KEY_LOCATION_ASKED, false)
        set(value) = prefs.edit().putBoolean(KEY_LOCATION_ASKED, value).apply()

    fun loadRides(): List<Ride> {
        val raw = prefs.getString(KEY_RIDES, null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i -> runCatching { arr.getJSONObject(i).toRide() }.getOrNull() }
        }.getOrDefault(emptyList())
    }

    fun saveRides(rides: List<Ride>) {
        val arr = JSONArray()
        rides.forEach { arr.put(it.toJson()) }
        prefs.edit().putString(KEY_RIDES, arr.toString()).apply()
    }

    fun loadSettings(): RideSettings {
        val raw = prefs.getString(KEY_SETTINGS, null) ?: return RideSettings()
        return runCatching {
            val o = JSONObject(raw)
            RideSettings(
                payee = Payee(o.optString("payeeName"), o.optString("payeeLink"), o.optString("payeeAccount")),
                rule = SplitRule.entries.firstOrNull { it.name == o.optString("rule") } ?: SplitRule.EVERYONE,
                prices = o.optJSONObject("prices")?.toPrices() ?: Prices(),
                fuelKind = o.optString("fuelKind", "휘발유"),
                bank = o.optString("bank"),
                accountNumber = o.optString("accountNumber"),
            )
        }.getOrDefault(RideSettings())
    }

    fun saveSettings(settings: RideSettings) {
        val o = JSONObject()
            .put("payeeName", settings.payee.name)
            .put("payeeLink", settings.payee.link)
            .put("payeeAccount", settings.payee.account)
            .put("rule", settings.rule.name)
            .put("prices", settings.prices.toJson())
            .put("fuelKind", settings.fuelKind)
            .put("bank", settings.bank)
            .put("accountNumber", settings.accountNumber)
        prefs.edit().putString(KEY_SETTINGS, o.toString()).apply()
    }

    fun clear() {
        prefs.edit().remove(KEY_RIDES).apply()
    }

    /** F-C01 진행 중인 길. 없으면 null을 넣어 지운다. */
    fun saveLive(snapshot: TrackerSnapshot?) {
        if (snapshot == null) {
            prefs.edit().remove(KEY_LIVE).apply()
            return
        }
        val o = JSONObject()
            .put("ride", snapshot.ride.toJson())
            .put("segStartKm", snapshot.segStartKm)
            .put("kmKnown", snapshot.kmKnown)
            .put("awaitingFirstDrive", snapshot.awaitingFirstDrive)
            .put("segTollgates", snapshot.segTollgates)
            .put("segExtras", JSONArray(snapshot.segExtras.map { JSONObject().put("kind", it.kind.name).put("won", it.won) }))
            .put("nextRiderId", snapshot.nextRiderId)
            .put("manualSeats", JSONArray(snapshot.manualSeats.map { it.name }))
            .put("forcedVacant", JSONArray(snapshot.forcedVacant.map { it.name }))
            .put("routeTollWon", snapshot.routeTollWon)
            .put("setup", snapshot.setup.toJson())
            .put("savedAt", snapshot.savedAt)
        snapshot.segStartEnergy?.let { o.put("segStartEnergy", it) }
        snapshot.destination?.let { o.put("destination", it) }
        snapshot.lastKm?.let { o.put("lastKm", it) }
        prefs.edit().putString(KEY_LIVE, o.toString()).apply()
    }

    fun loadLive(): TrackerSnapshot? {
        val raw = prefs.getString(KEY_LIVE, null) ?: return null
        return runCatching {
            val o = JSONObject(raw)
            TrackerSnapshot(
                ride = o.getJSONObject("ride").toRide(),
                segStartKm = o.getDouble("segStartKm"),
                kmKnown = o.optBoolean("kmKnown"),
                awaitingFirstDrive = o.optBoolean("awaitingFirstDrive"),
                segStartEnergy = if (o.has("segStartEnergy")) o.getDouble("segStartEnergy") else null,
                segTollgates = o.optInt("segTollgates"),
                segExtras = o.optJSONArray("segExtras")?.objects()?.mapNotNull { e ->
                    ExtraKind.entries.firstOrNull { it.name == e.optString("kind") }?.let { Extra(it, e.optInt("won")) }
                }.orEmpty(),
                nextRiderId = o.optInt("nextRiderId", 1),
                manualSeats = o.optJSONArray("manualSeats")?.seats().orEmpty(),
                forcedVacant = o.optJSONArray("forcedVacant")?.seats().orEmpty(),
                routeTollWon = o.optInt("routeTollWon"),
                destination = o.optStringOrNull("destination"),
                setup = o.optJSONObject("setup")?.toSetup() ?: TripSetup(),
                savedAt = o.optLong("savedAt"),
                lastKm = if (o.has("lastKm")) o.getDouble("lastKm") else null,
            )
        }.getOrNull()
    }

    private fun TripSetup.toJson() = JSONObject()
        .put("mode", mode.name)
        .put("names", JSONArray(names))
        .apply { groupId?.let { put("groupId", it) } }
        .apply { if (seats.isNotEmpty()) put("seats", JSONObject().apply { seats.forEach { (name, seat) -> put(name, seat.name) } }) }

    private fun JSONObject.toSetup() = TripSetup(
        mode = TripMode.entries.firstOrNull { it.name == optString("mode") } ?: TripMode.TRAVEL,
        groupId = optStringOrNull("groupId"),
        names = optJSONArray("names")?.let { arr -> (0 until arr.length()).map { arr.getString(it) } }.orEmpty(),
        seats = optJSONObject("seats")?.let { o ->
            o.keys().asSequence().mapNotNull { name -> Seat.entries.firstOrNull { it.name == o.optString(name) }?.let { name to it } }.toMap()
        }.orEmpty(),
    )

    private fun JSONArray.seats(): Set<Seat> = (0 until length()).mapNotNull { i -> Seat.entries.firstOrNull { it.name == optString(i) } }.toSet()

    /** K-02 카풀 그룹. */
    fun loadGroups(): List<CarpoolGroup> = loadArray(KEY_GROUPS) { o ->
        CarpoolGroup(
            id = o.getString("id"),
            name = o.getString("name"),
            members = o.optJSONArray("members")?.let { arr -> (0 until arr.length()).map { arr.getString(it) } }.orEmpty(),
            settleDay = o.optInt("settleDay", CarpoolGroup.LAST_DAY),
            lastUsedAt = o.optLong("lastUsedAt"),
        )
    }

    fun saveGroups(groups: List<CarpoolGroup>) = saveArray(KEY_GROUPS, groups) { g ->
        JSONObject()
            .put("id", g.id)
            .put("name", g.name)
            .put("members", JSONArray(g.members))
            .put("settleDay", g.settleDay)
            .put("lastUsedAt", g.lastUsedAt)
    }

    /** C-19 청구 현황. */
    fun loadCharges(): List<Charge> = loadArray(KEY_CHARGES) { o ->
        Charge(
            id = o.getString("id"),
            name = o.getString("name"),
            label = o.optString("label"),
            won = o.optInt("won"),
            billedAt = o.optLong("billedAt"),
            dueAt = o.optLong("dueAt"),
            paidAt = if (o.has("paidAt")) o.getLong("paidAt") else null,
            monthly = o.optBoolean("monthly"),
            paidWon = if (o.has("paidWon")) o.getInt("paidWon") else null,
            remindedAt = if (o.has("remindedAt")) o.getLong("remindedAt") else null,
            reminders = o.optInt("reminders"),
            earlierPaidWon = if (o.has("earlierPaidWon")) o.getInt("earlierPaidWon") else null,
            earlierPaidAt = if (o.has("earlierPaidAt")) o.getLong("earlierPaidAt") else null,
        )
    }

    fun saveCharges(charges: List<Charge>) = saveArray(KEY_CHARGES, charges) { c ->
        JSONObject()
            .put("id", c.id)
            .put("name", c.name)
            .put("label", c.label)
            .put("won", c.won)
            .put("billedAt", c.billedAt)
            .put("dueAt", c.dueAt)
            .put("monthly", c.monthly)
            .apply { c.paidAt?.let { put("paidAt", it) } }
            .apply { c.paidWon?.let { put("paidWon", it) } }
            .apply { c.remindedAt?.let { put("remindedAt", it).put("reminders", c.reminders) } }
            .apply { c.earlierPaidWon?.let { put("earlierPaidWon", it) } }
            .apply { c.earlierPaidAt?.let { put("earlierPaidAt", it) } }
    }

    /** F-C07 마지막으로 받은 공시 평균 유가와 받은 시각. 조회가 안 되면 이 값을 쓴다. */
    fun loadOfficial(): List<OfficialPrice> = loadArray(KEY_OFFICIAL) { o ->
        OfficialPrice(o.getString("fuel"), o.getInt("won"), LocalDate.parse(o.getString("date")))
    }

    var officialFetchedAt: Long
        get() = prefs.getLong(KEY_OFFICIAL_AT, 0L)
        set(value) = prefs.edit().putLong(KEY_OFFICIAL_AT, value).apply()

    fun saveOfficial(prices: List<OfficialPrice>) = saveArray(KEY_OFFICIAL, prices) { p ->
        JSONObject().put("fuel", p.fuelKind).put("won", p.won).put("date", p.date.toString())
    }

    private fun <T> loadArray(key: String, read: (JSONObject) -> T): List<T> {
        val raw = prefs.getString(key, null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i -> runCatching { read(arr.getJSONObject(i)) }.getOrNull() }
        }.getOrDefault(emptyList())
    }

    private fun <T> saveArray(key: String, items: List<T>, write: (T) -> JSONObject) {
        val arr = JSONArray()
        items.forEach { arr.put(write(it)) }
        prefs.edit().putString(key, arr.toString()).apply()
    }

    private fun Ride.toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("startedAt", startedAt)
        endedAt?.let { put("endedAt", it) }
        put("power", power.name)
        put("prices", prices.toJson())
        put("rule", rule.name)
        put("segments", JSONArray(segments.map { it.toJson() }))
        put("riders", JSONArray(riders.map { it.toJson() }))
        destination?.let { put("destination", it) }
        put("startPlace", startPlace.toJson())
        put("endPlace", endPlace.toJson())
        put("routeTollWon", routeTollWon)
        tollOverrideWon?.let { put("tollOverrideWon", it) }
        carEfficiency?.let { put("carEfficiency", it) }
        put("treated", treated)
        put("paid", JSONArray(paidRiderIds.toList()))
        put("mode", mode.name)
        groupId?.let { put("groupId", it) }
        put("tollPasses", JSONArray(tollPasses.map { it.toJson() }))
        put("costs", JSONArray(costs.map { it.toJson() }))
        unitWonOverride?.let { put("unitWonOverride", it) }
        put("optedOut", JSONArray(optedOut.toList()))
        savingAccepted?.let { put("savingAccepted", it) }
        game?.let { put("game", it.toJson()) }
        put("monthly", monthly)
        if (awaitingSettlementChoice) put("awaitingSettlementChoice", true)
        billingMonth?.let { put("billingMonth", it.toString()) }
        if (drivingMix.km > 0.0) put("cityKm", drivingMix.cityKm).put("highwayKm", drivingMix.highwayKm)
    }

    private fun JSONObject.toRide(): Ride = Ride(
        id = getString("id"),
        startedAt = getLong("startedAt"),
        endedAt = if (has("endedAt")) getLong("endedAt") else null,
        power = Power.entries.firstOrNull { it.name == optString("power") } ?: Power.ELECTRIC,
        prices = optJSONObject("prices")?.toPrices() ?: Prices(),
        rule = SplitRule.entries.firstOrNull { it.name == optString("rule") } ?: SplitRule.EVERYONE,
        segments = getJSONArray("segments").objects().map { it.toSegment() },
        riders = getJSONArray("riders").objects().map { it.toRider() },
        destination = optStringOrNull("destination"),
        startPlace = optJSONObject("startPlace")?.toPlace() ?: Place(),
        endPlace = optJSONObject("endPlace")?.toPlace() ?: Place(),
        routeTollWon = optInt("routeTollWon"),
        tollOverrideWon = if (has("tollOverrideWon")) getInt("tollOverrideWon") else null,
        carEfficiency = if (has("carEfficiency")) getDouble("carEfficiency") else null,
        treated = optBoolean("treated"),
        paidRiderIds = optJSONArray("paid")?.let { arr -> (0 until arr.length()).map { arr.getInt(it) }.toSet() }.orEmpty(),
        mode = TripMode.entries.firstOrNull { it.name == optString("mode") } ?: TripMode.TRAVEL,
        groupId = optStringOrNull("groupId"),
        tollPasses = optJSONArray("tollPasses")?.objects()?.map { it.toTollPass() }.orEmpty(),
        costs = optJSONArray("costs")?.objects()?.map { it.toCost() }.orEmpty(),
        unitWonOverride = if (has("unitWonOverride")) getInt("unitWonOverride") else null,
        optedOut = optJSONArray("optedOut")?.ints().orEmpty(),
        savingAccepted = if (has("savingAccepted")) getBoolean("savingAccepted") else null,
        game = optJSONObject("game")?.toGame(),
        monthly = optBoolean("monthly", true),
        awaitingSettlementChoice = optBoolean("awaitingSettlementChoice"),
        billingMonth = optStringOrNull("billingMonth")?.let { runCatching { YearMonth.parse(it) }.getOrNull() },
        drivingMix = DrivingMix(optDouble("cityKm", 0.0), optDouble("highwayKm", 0.0)),
    )

    private fun TollPass.toJson() = JSONObject().apply {
        put("at", at)
        put("km", km)
        put("aboard", JSONArray(aboard.toList()))
        put("name", name)
        won?.let { put("won", it) }
    }

    private fun JSONObject.toTollPass() = TollPass(
        at = optLong("at"),
        km = optDouble("km", 0.0),
        aboard = optJSONArray("aboard")?.ints().orEmpty(),
        name = optString("name"),
        won = if (has("won")) getInt("won") else null,
    )

    private fun Cost.toJson() = JSONObject()
        .put("name", name)
        .put("won", won)
        .put("sharedBy", JSONArray(sharedBy.toList()))
        .put("source", source.name)
        .put("paidBy", paidBy)
        .put("at", at)

    private fun JSONObject.toCost() = Cost(
        name = optString("name"),
        won = optInt("won"),
        sharedBy = optJSONArray("sharedBy")?.ints().orEmpty(),
        source = ExpenseSource.entries.firstOrNull { it.name == optString("source") } ?: ExpenseSource.DRIVING,
        paidBy = optInt("paidBy", DRIVER_ID),
        at = optLong("at"),
    )

    private fun RideGame.toJson() = JSONObject()
        .put("mode", mode.name)
        .put("targetWon", targetWon)
        .put("players", JSONArray(players.toList()))
        .put("loser", loser)
        .put("loserShare", loserShare)
        .put("game", game)
        .put("whole", whole)
        .apply { costIndexes?.let { put("costIndexes", JSONArray(it)) } }

    private fun JSONObject.toGame(): RideGame? {
        val mode = GameMode.entries.firstOrNull { it.name == optString("mode") } ?: return null
        return RideGame(
            mode = mode,
            targetWon = optInt("targetWon"),
            players = optJSONArray("players")?.ints().orEmpty(),
            loser = optInt("loser"),
            loserShare = optDouble("loserShare", if (mode == GameMode.ALL_IN) 1.0 else 0.5),
            game = optString("game"),
            whole = optBoolean("whole"),
            costIndexes = optJSONArray("costIndexes")?.let { arr -> (0 until arr.length()).map { arr.getInt(it) } },
        )
    }

    private fun Segment.toJson() = JSONObject().apply {
        put("startKm", startKm)
        put("endKm", endKm)
        put("riders", JSONArray(riderIds.toList()))
        energyUsed?.let { put("energy", it) }
        put("tollgates", tollgates)
        put("extras", JSONArray(extras.map { JSONObject().put("kind", it.kind.name).put("won", it.won) }))
    }

    private fun JSONObject.toSegment() = Segment(
        startKm = getDouble("startKm"),
        endKm = getDouble("endKm"),
        riderIds = getJSONArray("riders").let { arr -> (0 until arr.length()).map { arr.getInt(it) }.toSet() },
        energyUsed = if (has("energy")) getDouble("energy") else null,
        tollgates = optInt("tollgates"),
        extras = optJSONArray("extras")?.objects()?.mapNotNull { e ->
            ExtraKind.entries.firstOrNull { it.name == e.optString("kind") }?.let { Extra(it, e.optInt("won")) }
        }.orEmpty(),
    )

    private fun Rider.toJson() = JSONObject().apply {
        put("id", id)
        put("seat", seat.name)
        put("boardedAt", boardedAt)
        put("boardedKm", boardedKm)
        put("boardedPlace", boardedPlace.toJson())
        alightedAt?.let { put("alightedAt", it) }
        alightedKm?.let { put("alightedKm", it) }
        put("alightedPlace", alightedPlace.toJson())
        name?.let { put("name", it) }
        put("guessed", nameGuessed)
        sameAs?.let { put("sameAs", it) }
    }

    private fun JSONObject.toRider() = Rider(
        id = getInt("id"),
        seat = Seat.valueOf(getString("seat")),
        boardedAt = getLong("boardedAt"),
        boardedKm = getDouble("boardedKm"),
        boardedPlace = optJSONObject("boardedPlace")?.toPlace() ?: Place(),
        alightedAt = if (has("alightedAt")) getLong("alightedAt") else null,
        alightedKm = if (has("alightedKm")) getDouble("alightedKm") else null,
        alightedPlace = optJSONObject("alightedPlace")?.toPlace() ?: Place(),
        name = optStringOrNull("name"),
        nameGuessed = optBoolean("guessed"),
        sameAs = if (has("sameAs")) getInt("sameAs") else null,
    )

    private fun Place.toJson() = JSONObject().apply {
        address?.let { put("address", it) }
        point?.let { put("lat", it.latitude); put("lon", it.longitude) }
    }

    private fun JSONObject.toPlace() = Place(
        address = optStringOrNull("address"),
        point = if (has("lat") && has("lon")) GeoPoint(getDouble("lat"), getDouble("lon")) else null,
    )

    private fun Prices.toJson() = JSONObject()
        .put("wonPerKwh", wonPerKwh)
        .put("wonPerLiter", wonPerLiter)
        .put("kmPerKwh", kmPerKwh)
        .put("kmPerLiter", kmPerLiter)
        .apply { officialDate?.let { put("officialDate", it) } }
        .apply { carModel?.let { put("carModel", it.name) } }

    private fun JSONObject.toPrices() = Prices(
        wonPerKwh = optInt("wonPerKwh", Prices().wonPerKwh),
        wonPerLiter = optInt("wonPerLiter", Prices().wonPerLiter),
        kmPerKwh = optDouble("kmPerKwh", Prices().kmPerKwh),
        kmPerLiter = optDouble("kmPerLiter", Prices().kmPerLiter),
        officialDate = optStringOrNull("officialDate"),
        carModel = CarModel.of(optStringOrNull("carModel")),
    )

    private fun JSONArray.objects(): List<JSONObject> = (0 until length()).map { getJSONObject(it) }

    private fun JSONArray.ints(): Set<Int> = (0 until length()).map { getInt(it) }.toSet()

    private fun JSONObject.optStringOrNull(key: String): String? = if (has(key) && !isNull(key)) getString(key) else null

    private companion object {
        const val KEY_RIDES = "rides"
        const val KEY_LIVE = "live_ride"
        const val KEY_GROUPS = "carpool_groups"
        const val KEY_CHARGES = "charges"
        const val KEY_SETTINGS = "settings"
        const val KEY_OFFICIAL = "official_prices"
        const val KEY_OFFICIAL_AT = "official_fetched_at"
        const val KEY_LOCATION_ASKED = "location_asked"
    }
}
