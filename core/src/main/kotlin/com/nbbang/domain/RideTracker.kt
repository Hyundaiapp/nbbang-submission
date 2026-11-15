package com.nbbang.domain

sealed interface RideEvent {
    data object Started : RideEvent
    data class Boarded(val rider: Rider) : RideEvent
    data class Alighted(val rider: Rider) : RideEvent
    data class Finished(val ride: Ride) : RideEvent

    /** 동승자 없이 끝난 길. 장부에 남기지 않는다. */
    data object FinishedAlone : RideEvent
}

/**
 * 차 신호로 함께 탄 길을 기록한다. 화면이나 SDK와 상관없는 순수 로직이다.
 *
 * - 차 신호로 길을 시작하거나, K-03에서 선택한 동승자와 먼저 열고 첫 D부터 거리·에너지를 잰다.
 * - 자리 탑승 신호가 [stableMs] 동안 바뀐 채로 있으면 타거나 내린 것으로 본다. 자세를 고칠 때 잠깐 비는 것을 거른다.
 * - 탄 사람이 바뀔 때마다 구간을 끊고, 구간마다 거리와 쓴 에너지를 잰다.
 * - 길은 사람이 끝낸다([finish]: C-03 주행 종료 → K-04 "카풀 주행 종료!", T-03 "N빵하기!"). 문이나 기어로는 끝내지 않는다.
 *   휴게소·주유소에 들러 문을 열어도 한 길로 이어지게. 앱을 오래 꺼 두었다 켜면 [RESTORE_LIMIT_MS] 기준으로 그때 끝낸다.
 * - 탑승 센서가 없는 자리는 [setManual]로 사람이 탔다고 알려 준다.
 * - N빵 앱은 좌석 센서를 쓰지 않는다(뒷줄 가운데 자리 미인식, 11/15). [CarState.seated]가 늘 비어 있어서
 *   출발 명단(T-01·K-03), [boardNow](C-04), [alightNow](C-05)로만 타고 내린다. 자리 신호를 다루는 부분은 신호가 오는 차를 위해 남겨 둔다.
 */
class RideTracker(private val stableMs: Long = SEAT_STABLE_MS) {
    var ride: Ride? = null
        private set
    var awaitingFirstDrive = false
        private set

    private var lastCar: CarState? = null
    private var segStartKm = 0.0
    private var kmKnown = false
    private var segStartEnergy: Double? = null
    private var segTollgates = 0
    private var segExtras = mutableListOf<Extra>()
    private var nextRiderId = 1
    private val pending = mutableMapOf<Seat, PendingSeat>()
    private val manualSeats = mutableSetOf<Seat>()

    /** 1km마다 도심·고속을 나눈다. 공인연비 기준연비([Prices.carModel])에 쓴다. */
    private val mix = DrivingMixMeter()

    /** C-05로 내리게 한 자리. 센서가 아직 앉아 있다고 해도 한 번 빈자리가 될 때까지는 다시 태우지 않는다. */
    private val forcedVacant = mutableSetOf<Seat>()

    // 내비 경로는 보통 출발(D) 전에 정한다. 길이 시작되면 넘겨준다.
    private var routeTollWon = 0
    private var destination: String? = null
    private var setup = TripSetup()

    private data class PendingSeat(val occupied: Boolean, val since: Long, val km: Double?, val energy: Double?, val place: Place)

    /**
     * F-C01 진행 중인 길을 저장할 모습. 앱이나 차가 다시 켜지면 [restore]로 이어 간다.
     * [savedAt]과 [TrackerSnapshot.lastKm]을 빼면 차 신호로 타고 내릴 때만 달라진다. 길이 없으면 null.
     */
    fun snapshot(savedAt: Long = 0L): TrackerSnapshot? = ride?.let {
        TrackerSnapshot(
            ride = it,
            segStartKm = segStartKm,
            kmKnown = kmKnown,
            segStartEnergy = segStartEnergy,
            segTollgates = segTollgates,
            segExtras = segExtras.toList(),
            nextRiderId = nextRiderId,
            manualSeats = manualSeats.toSet(),
            forcedVacant = forcedVacant.toSet(),
            routeTollWon = routeTollWon,
            destination = destination,
            setup = setup,
            savedAt = savedAt,
            lastKm = lastCar?.odometerKm,
            awaitingFirstDrive = awaitingFirstDrive,
        )
    }

    /** 저장해 둔 길을 이어 간다. 자리 신호는 다음 [update]에서 다시 맞춘다. */
    fun restore(snapshot: TrackerSnapshot) {
        ride = snapshot.ride
        segStartKm = snapshot.segStartKm
        kmKnown = snapshot.kmKnown
        segStartEnergy = snapshot.segStartEnergy
        segTollgates = snapshot.segTollgates
        segExtras = snapshot.segExtras.toMutableList()
        nextRiderId = snapshot.nextRiderId
        manualSeats.clear()
        manualSeats += snapshot.manualSeats
        forcedVacant.clear()
        forcedVacant += snapshot.forcedVacant
        routeTollWon = snapshot.routeTollWon
        destination = snapshot.destination
        setup = snapshot.setup
        awaitingFirstDrive = snapshot.awaitingFirstDrive
        pending.clear()
        mix.reset()
        lastCar = null
    }

    fun startCarpool(
        trip: TripSetup,
        car: CarState,
        now: Long,
        place: Place,
        prices: Prices,
        rule: SplitRule,
        power: Power,
    ): List<RideEvent> {
        if (ride != null || car.gear != Gear.PARK || trip.mode != TripMode.CARPOOL ||
            trip.groupId == null || trip.names.size !in 1..Seat.passengers.size
        ) return emptyList()
        return startPreparedRide(trip, car, now, place, prices, rule, power)
    }

    fun startTravel(
        trip: TripSetup,
        car: CarState,
        now: Long,
        place: Place,
        prices: Prices,
        rule: SplitRule,
        power: Power,
    ): List<RideEvent> {
        if (ride != null || car.gear != Gear.PARK || trip.mode != TripMode.TRAVEL ||
            trip.names.size !in 1..Seat.passengers.size
        ) return emptyList()
        return startPreparedRide(trip, car, now, place, prices, rule, power)
    }

    private fun startPreparedRide(
        trip: TripSetup,
        car: CarState,
        now: Long,
        place: Place,
        prices: Prices,
        rule: SplitRule,
        power: Power,
    ): List<RideEvent> {
        setup = trip
        lastCar = car
        val events = startRide(car, now, place, prices, rule, power, trip.names)
        awaitingFirstDrive = true
        return events
    }

    fun update(
        car: CarState,
        now: Long,
        place: Place = Place(),
        prices: Prices = Prices(),
        rule: SplitRule = SplitRule.EVERYONE,
        power: Power = car.power,
    ): List<RideEvent> {
        lastCar = car
        val events = mutableListOf<RideEvent>()
        if (ride == null) {
            if (!car.moving) return events
            val carpool = setup.mode == TripMode.CARPOOL && setup.groupId != null
            if (carpool && car.gear != Gear.DRIVE) return events
            return startRide(car, now, place, prices, rule, power, if (carpool) setup.names else emptyList())
        }
        if (awaitingFirstDrive) {
            if (car.gear != Gear.DRIVE) return events
            awaitingFirstDrive = false
            segStartKm = car.odometerKm ?: segStartKm
            kmKnown = car.odometerKm != null
            segStartEnergy = energyOf(car)
            edit { current -> current.copy(riders = current.riders.map {
                if (it.aboard) it.copy(boardedKm = segStartKm) else it
            }) }
            bindToSensedSeats(car)
            if (ride?.mode == TripMode.TRAVEL) {
                Seat.passengers.filter { occupied(car, it) && riderAt(it) == null }.forEach { seat ->
                    events += board(seat, now, car.odometerKm, energyOf(car), place)
                }
            }
        }
        // 주행거리나 배터리 값이 길이 시작된 뒤에야 처음 오면 그때부터 잰다.
        if (!kmKnown && car.odometerKm != null) {
            segStartKm = car.odometerKm
            kmKnown = true
        }
        if (segStartEnergy == null) segStartEnergy = energyOf(car)
        // 도심·고속 거리는 첫 D부터 잰다(구간과 같다).
        if (!awaitingFirstDrive && kmKnown) car.odometerKm?.let { km ->
            mix.odometer(now, km)?.let { (driven, highway) -> edit { it.copy(drivingMix = it.drivingMix.plus(driven, highway)) } }
        }
        // 짐 자리 표시는 그 자리가 비었다고 확인될 때만 지운다(앱을 다시 켠 직후처럼 값이 아직 없으면 둔다).
        forcedVacant.retainAll { car.seated[it] != false }

        for (seat in Seat.passengers) {
            // 자리 신호를 아직 못 받았으면 바뀐 게 없다고 본다. 앱을 다시 켠 직후 첫 신호가 늦게 와도
            // 앉아 있던 사람을 내린 것으로 치지 않는다. 손으로 태운 자리는 신호와 상관없다.
            if (seat !in car.seated && seat !in manualSeats) {
                pending.remove(seat)
                continue
            }
            // K-03에서 고른 사람을 수동으로 둔 자리에 센서가 사람을 잡았다. 이제부터 그 자리는 센서를 따른다(내릴 때 안다).
            if (car.seated[seat] == true && seat in manualSeats && riderAt(seat)?.let(::waitingFromSetup) == true) {
                manualSeats -= seat
            }
            val nowOccupied = occupied(car, seat)
            val aboard = riderAt(seat) != null
            if (nowOccupied == aboard) {
                pending.remove(seat)
                continue
            }
            val known = pending[seat]
            if (known == null || known.occupied != nowOccupied) {
                pending[seat] = PendingSeat(nowOccupied, now, car.odometerKm, energyOf(car), place)
            }
            val change = pending.getValue(seat)
            if (now - change.since < stableMs) continue
            pending.remove(seat)
            if (change.occupied) {
                // K-03에서 고른 사람이 첫 D 때 차에 없어 수동 자리에 있다가(가는 길에 태움) 이제 앉았다.
                // 새 사람으로 세지 않고 그 사람을 이 자리로 옮긴다. K-03에서 고른 사람은 0km부터 포함이다.
                if (!seatWaitingRider(seat)) events += board(seat, change.since, change.km, change.energy, change.place)
            } else {
                events += alight(seat, change.since, change.km, change.energy, change.place)
            }
        }

        return events
    }

    /** 길을 지금 끝낸다. 아직 앉아 있는 사람은 여기서 내린 것으로 본다. 열린 길이 없으면 null. */
    fun finish(now: Long, car: CarState? = lastCar, place: Place = Place()): RideEvent? {
        if (ride == null) return null
        cut(car?.odometerKm, car?.let(::energyOf))
        val current = ride ?: return null
        val neverDrove = awaitingFirstDrive && current.segments.isEmpty() && current.costs.isEmpty()
        val endKm = segStartKm
        val done = current.copy(
            endedAt = now,
            endPlace = place,
            riders = current.riders.map {
                if (it.aboard) it.copy(alightedAt = now, alightedKm = endKm, alightedPlace = place) else it
            },
            carEfficiency = car?.let(::efficiencyOf) ?: current.carEfficiency,
            destination = destination ?: current.destination,
            routeTollWon = routeTollWon,
        )
        reset()
        // 함께 나눈 게 없으면(0km, 톨게이트·비용 없음) 장부에 남기지 않는다. 0원 정산 화면이나 K-05가 뜨지 않게.
        return if (neverDrove || done.riders.isEmpty() || done.sharedNothing) RideEvent.FinishedAlone else RideEvent.Finished(done)
    }

    /** 지금 끝낸다면 어떤 길인지. 진행 중인 구간을 지금 위치에서 닫아 본다. 바꾸지는 않는다. */
    fun preview(car: CarState? = lastCar): Ride? {
        val current = ride ?: return null
        val km = if (awaitingFirstDrive) segStartKm else maxOf(car?.odometerKm ?: segStartKm, segStartKm)
        val segment = Segment(
            segStartKm, km, aboardIds(current), used(car?.let(::energyOf)), segTollgates, segExtras.toList(),
        )
        val segments = if (segment.km > 0 || segment.tollgates > 0 || segment.extras.isNotEmpty()) current.segments + segment else current.segments
        return current.copy(
            segments = segments,
            routeTollWon = routeTollWon,
            destination = destination ?: current.destination,
            carEfficiency = car?.let(::efficiencyOf) ?: current.carEfficiency,
        )
    }

    /** 내비에 경로가 걸렸다. 목적지와 예상 통행료를 기억한다. */
    fun setRoute(name: String?, tollWon: Int) {
        destination = name
        routeTollWon = tollWon.coerceAtLeast(0)
        ride = ride?.copy(destination = name, routeTollWon = routeTollWon)
    }

    /** 톨게이트를 지났다. 통행료를 이 구간에 탄 사람끼리 나누는 근거가 된다. */
    /**
     * 하이패스를 지났다(C-06). 금액 없이 한 건 남기고, 새로 남긴 건의 순번([Ride.tollPasses])을 돌려준다.
     * 같은 이름의 톨게이트를 [TOLL_DEDUP_MS] 안에 또 지나면 한 건으로 보고 null을 돌려준다.
     */
    fun tollgatePassed(now: Long = 0L, name: String = ""): Int? {
        if (awaitingFirstDrive) return null
        val current = ride ?: return null
        val last = current.tollPasses.lastOrNull()
        if (now > 0L && last != null && last.name == name && now - last.at in 0 until TOLL_DEDUP_MS) return null
        segTollgates += 1
        val km = maxOf(lastCar?.odometerKm ?: segStartKm, segStartKm)
        val pass = TollPass(now, km, aboardIds(current) + DRIVER_ID, name)
        ride = current.copy(tollPasses = current.tollPasses + pass)
        return current.tollPasses.size
    }

    /** 출발 전에 고른 모드·그룹. 다음 길이 시작될 때 [Ride]에 넣는다. */
    fun prepare(setup: TripSetup) {
        this.setup = setup
        ride = ride?.copy(mode = setup.mode, groupId = setup.groupId)
    }

    /**
     * 진행 중인 길에서 사람이 정한 것(톨비, 추가 비용, 단가, opt in/out, 절약 기여, 게임, 재탑승)을 바꾼다.
     * 구간과 타고 내린 시각·거리는 차 신호로만 바뀌므로 [transform]에서 건드리지 않는다(이름·재탑승 표시는 괜찮다).
     */
    fun edit(transform: (Ride) -> Ride) {
        ride = ride?.let(transform)
    }

    /** 지금 탄 사람(운전자 포함). 추가 비용의 기본 분담 대상이다. */
    val aboardNow: Set<Int> get() = ride?.let { aboardIds(it) + DRIVER_ID } ?: emptySet()

    /** 지금 타고 있는 사람끼리 나눌 비용을 더한다. 열린 길이 없으면 false. */
    fun addExtra(extra: Extra): Boolean {
        if (ride == null || extra.won <= 0) return false
        segExtras += extra
        return true
    }

    fun setTollOverride(won: Int?) {
        ride = ride?.copy(tollOverrideWon = won)
    }

    fun treat(on: Boolean = true) {
        ride = ride?.copy(treated = on)
    }

    fun rename(riderId: Int, name: String?, guessed: Boolean = false) {
        val current = ride ?: return
        ride = current.copy(riders = current.riders.map { if (it.id == riderId) it.copy(name = name, nameGuessed = guessed) else it })
    }

    /** 탑승 센서가 없는 자리에 사람이 탔다(또는 내렸다)고 알린다. 다음 [update]에서 반영한다. */
    fun setManual(seat: Seat, aboard: Boolean) {
        if (aboard) manualSeats += seat else manualSeats -= seat
    }

    val aboardCount: Int get() = ride?.riders?.count { it.aboard } ?: 0

    /**
     * C-04 수동 추가. 빈 자리에 [count]명을 지금 바로 태운다(센서 안정 시간을 기다리지 않는다).
     * 센서가 앉았다고 했지만 아직 안정 시간이 안 지난 자리부터 채우고, 그 자리는 센서를 따른다.
     * 나머지 자리는 센서가 빈자리라고 해도 내릴 때까지 탄 것으로 본다. 태운 사람을 돌려주고, 자리가 모자라면 있는 만큼만.
     */
    fun boardNow(count: Int, now: Long, place: Place = Place()): List<Rider> {
        if (ride == null || count <= 0) return emptyList()
        val car = lastCar
        if (!awaitingFirstDrive && !kmKnown && car?.odometerKm != null) {
            segStartKm = car.odometerKm
            kmKnown = true
        }
        // 창가 먼저, 가운데는 뒤에(K-03 자리 고르기와 같은 순서).
        val seats = Seat.preferred.filter { riderAt(it) == null }.sortedByDescending { car?.seated?.get(it) == true }
        return seats.take(count).map { seat ->
            if (car?.seated?.get(seat) != true) manualSeats += seat
            forcedVacant -= seat
            pending.remove(seat)
            (board(seat, now, car?.odometerKm, car?.let(::energyOf), place) as RideEvent.Boarded).rider
        }
    }

    /**
     * C-04에서 센서가 태운 사람보다 적게 확인했다. 남는 사람은 짐 같은 것을 사람으로 잘못 센 것이라 처음부터 없던 것으로 지운다
     * (내린 것으로 치면 그동안 달린 거리를 내게 된다). 그 자리는 비었다고 확인될 때까지 짐 자리로 두어 다시 태우지 않는다.
     */
    fun discard(riderIds: Collection<Int>) {
        val current = ride ?: return
        val gone = current.riders.filter { it.id in riderIds && it.aboard }
        if (gone.isEmpty()) return
        val ids = gone.mapTo(HashSet()) { it.id }
        gone.forEach { rider ->
            pending.remove(rider.seat)
            manualSeats -= rider.seat
            if (lastCar?.seated?.get(rider.seat) == true) forcedVacant += rider.seat
        }
        ride = current.copy(
            riders = current.riders.filterNot { it.id in ids },
            segments = current.segments.map { s -> if (s.riderIds.any { it in ids }) s.copy(riderIds = s.riderIds - ids) else s },
        )
    }

    /** C-05 하차 처리. 지금 바로 내린다. 내린 사람을 돌려준다. 이미 내렸거나 없는 사람이면 null. */
    fun alightNow(riderId: Int, now: Long, place: Place = Place()): Rider? {
        val seat = ride?.riders?.firstOrNull { it.id == riderId && it.aboard }?.seat ?: return null
        val car = lastCar
        pending.remove(seat)
        if (car?.seated?.get(seat) == true) forcedVacant += seat
        return (alight(seat, now, car?.odometerKm, car?.let(::energyOf), place) as RideEvent.Alighted).rider
    }

    private fun startRide(
        car: CarState,
        now: Long,
        place: Place,
        prices: Prices,
        rule: SplitRule,
        power: Power,
        names: List<String>,
    ): List<RideEvent> {
        ride = Ride(
            id = "r$now",
            startedAt = now,
            power = power,
            prices = prices,
            rule = rule,
            destination = destination,
            startPlace = place,
            routeTollWon = routeTollWon,
            carEfficiency = if (power == Power.ELECTRIC) car.kmPerKwh else car.kmPerLiter,
            mode = setup.mode,
            groupId = setup.groupId,
        )
        segStartKm = car.odometerKm ?: 0.0
        kmKnown = car.odometerKm != null
        segStartEnergy = energyOf(car)
        segTollgates = 0
        segExtras = mutableListOf()
        nextRiderId = 1
        mix.reset()
        val occupiedSeats = Seat.passengers.filter { occupied(car, it) }
        plannedSeats(names, occupiedSeats, car)?.let { seats ->
            // K-03에서 자리를 골랐다. 그 자리에 바로 앉힌다. 센서가 아직 안 잡은 자리는 수동으로 두고,
            // 앉으면 그때부터 센서를 따른다(update). 고른 사람이 아닌 누가 이미 앉아 있으면 이름 없이 태운다.
            manualSeats += seats.filter { it !in occupiedSeats }
            pending.clear()
            val strangers = occupiedSeats.filter { it !in seats }
            return listOf(RideEvent.Started) +
                names.mapIndexed { index, name -> board(seats[index], now, car.odometerKm, energyOf(car), place, name) } +
                strangers.map { board(it, now, car.odometerKm, energyOf(car), place) }
        }
        // 센서가 확인한 자리를 먼저 사용하고, 부족한 인원은 센서가 없는 자리부터 채운다(창가 먼저, 가운데는 뒤에).
        val manualBoarding = Seat.preferred.filterNot { it in occupiedSeats }
            .sortedBy { car.seated[it] != null }
            .take((names.size - occupiedSeats.size).coerceAtLeast(0))
        manualSeats += manualBoarding
        pending.clear()
        return listOf(RideEvent.Started) + (occupiedSeats + manualBoarding).mapIndexed { index, seat ->
            board(seat, now, car.odometerKm, energyOf(car), place, names.getOrNull(index))
        }
    }

    /**
     * K-03·T-01에서 정한 사람은 주차 중에 수동 좌석에 둘 수 있다. 첫 D에서 센서가 감지한 자리로 옮긴다.
     * 센서는 탑승자 이름을 모르므로 출발 명단 순서대로 붙인다.
     * 센서가 잡히지 않은 사람은 수동 그대로 둔다(C-03에서 하차를 누른다).
     */
    private fun bindToSensedSeats(car: CarState) {
        val current = ride ?: return
        if (setup.seatPlan != null) return moveToSensedSeats(car)
        val named = current.riders.filter { it.aboard && it.seat in Seat.passengers }.sortedBy { it.id }
        val sensed = Seat.passengers.filter { car.seated[it] == true }
        if (named.isEmpty() || sensed.isEmpty()) return
        val seatOf = mutableMapOf<Int, Seat>()
        named.zip(sensed).forEach { (rider, seat) -> seatOf[rider.id] = seat }
        // 센서 자리보다 사람이 많으면 남은 사람은 센서가 잡지 않은 자리에 수동으로 둔다(지금 자리가 비어 있으면 그대로).
        val spare = Seat.passengers.filter { it !in sensed }.toMutableList()
        named.drop(sensed.size).forEach { rider ->
            val seat = if (rider.seat in spare) rider.seat else spare.first()
            spare -= seat
            seatOf[rider.id] = seat
        }
        manualSeats.removeAll(named.map { it.seat }.toSet())
        named.drop(sensed.size).forEach { manualSeats += seatOf.getValue(it.id) }
        ride = current.copy(riders = current.riders.map { rider -> seatOf[rider.id]?.let { rider.copy(seat = it) } ?: rider })
    }

    /**
     * K-03에서 고른 자리([TripSetup.seats])를 [names] 순서대로. 고른 자리가 이름마다 하나씩, 서로 다르게 있을 때만 쓴다.
     * 고른 자리에 센서가 없는데 고르지 않은 자리에 센서가 잡혀 있으면(자리를 바꿔 앉음) 순서대로 그 자리로 옮긴다.
     */
    private fun plannedSeats(names: List<String>, occupiedSeats: List<Seat>, car: CarState): List<Seat>? {
        if (names != setup.names) return null
        val seats = setup.seatPlan ?: return null
        val moved = seats.toMutableList()
        val free = occupiedSeats.filter { it !in seats }.toMutableList()
        // 고른 자리 센서가 비었으면 확실히 다른 데 앉았으니 먼저 옮긴다. 그래도 잡힌 자리가 남으면 센서 없는 자리(3열 등)를
        // 고른 사람이 거기 앉은 것으로 본다. 그 자리를 이름 없는 동승자로 따로 세면 같은 사람을 두 번 센다.
        val empty = moved.indices.filter { moved[it] !in occupiedSeats && car.seated[moved[it]] == false }
        val unsensed = moved.indices.filter { moved[it] !in occupiedSeats && car.seated[moved[it]] == null }
        (empty + unsensed).forEach { i -> if (free.isNotEmpty()) moved[i] = free.removeAt(0) }
        return moved
    }

    /**
     * 첫 D에서 K-03 자리를 센서와 맞춘다. 고른 자리에 앉은 사람은 그대로 두고, 고른 자리 센서가 비어 있는데
     * 아무도 없는 자리에 센서가 잡혔으면 그 사람을 그리로 옮긴다(자리를 바꿔 앉음). 그래도 잡힌 자리가 남으면 센서 없는
     * 자리를 고른 사람을 옮긴다. 옮길 데가 없는 사람은 하차로 보지 않고 수동 자리로 둔다(가는 길에 태움, K-03은 0km부터).
     */
    private fun moveToSensedSeats(car: CarState) {
        val current = ride ?: return
        val sensed = Seat.passengers.filter { car.seated[it] == true }
        val taken = current.riders.filter { it.aboard }.map { it.seat }.toSet()
        val free = sensed.filter { it !in taken }.toMutableList()
        val named = current.riders.filter { it.aboard && it.name in setup.names }.sortedBy { it.id }
        val away = named.filter { car.seated[it.seat] == false }
        val unsensed = named.filter { car.seated[it.seat] == null }
        val seatOf = mutableMapOf<Int, Seat>()
        (away + unsensed).forEach { rider -> if (free.isNotEmpty()) seatOf[rider.id] = free.removeAt(0) }
        // 출발 때 앉아 있다가 첫 D 전에 일어난 사람도 0km 하차가 아니라 기다리는 사람으로 둔다.
        away.filter { it.id !in seatOf }.forEach { manualSeats += it.seat }
        if (seatOf.isEmpty()) return
        seatOf.forEach { (id, seat) ->
            manualSeats -= current.riders.first { it.id == id }.seat
            manualSeats -= seat
        }
        ride = current.copy(riders = current.riders.map { rider -> seatOf[rider.id]?.let { rider.copy(seat = it) } ?: rider })
    }

    private fun board(seat: Seat, at: Long, km: Double?, energy: Double?, place: Place, name: String? = null): RideEvent {
        cut(km, energy)
        val current = ride!!
        val rider = Rider(nextRiderId++, seat, at, segStartKm, place, name = name)
        ride = current.copy(riders = current.riders + rider)
        return RideEvent.Boarded(rider)
    }

    private fun alight(seat: Seat, at: Long, km: Double?, energy: Double?, place: Place): RideEvent {
        cut(km, energy)
        val current = ride!!
        val rider = riderAt(seat)!!.copy(alightedAt = at, alightedKm = segStartKm, alightedPlace = place)
        ride = current.copy(riders = current.riders.map { if (it.id == rider.id) rider else it })
        manualSeats -= seat
        return RideEvent.Alighted(rider)
    }

    /** 진행 중인 구간을 [km]에서 닫고 새 구간을 연다. 거리도 비용도 없는 구간은 남기지 않는다. */
    private fun cut(km: Double?, energy: Double?) {
        val current = ride ?: return
        val endKm = if (!awaitingFirstDrive && kmKnown && km != null) maxOf(km, segStartKm) else segStartKm
        val segment = Segment(segStartKm, endKm, aboardIds(current), used(energy), segTollgates, segExtras.toList())
        if (segment.km > 0 || segment.tollgates > 0 || segment.extras.isNotEmpty()) {
            ride = current.copy(segments = current.segments + segment)
        }
        segStartKm = endKm
        segStartEnergy = energy ?: segStartEnergy
        segTollgates = 0
        segExtras = mutableListOf()
    }

    /** 구간 시작과 지금의 배터리 에너지 차이. 충전되었거나(음수) 값이 없으면 null이다. */
    private fun used(energy: Double?): Double? {
        if (awaitingFirstDrive) return null
        val start = segStartEnergy ?: return null
        val end = energy ?: return null
        return (start - end).takeIf { it >= 0.0 }
    }

    private fun aboardIds(ride: Ride): Set<Int> = ride.riders.filter { it.aboard }.map { it.id }.toSet()

    private fun riderAt(seat: Seat): Rider? = ride?.riders?.lastOrNull { it.seat == seat && it.aboard }

    private fun occupied(car: CarState, seat: Seat): Boolean =
        (car.seated[seat] == true && seat !in forcedVacant) || seat in manualSeats

    private fun energyOf(car: CarState): Double? = if (ride?.power == Power.ELECTRIC) car.batteryKwh else null

    private fun efficiencyOf(car: CarState): Double? = if (ride?.power == Power.ELECTRIC) car.kmPerKwh else car.kmPerLiter

    /**
     * 함께 나눈 게 없는 진행 중인 길([sharedNothing])을 장부에 남기지 않고 버린다. 혼자 픽업하러 간 길이 저절로 기록된 채
     * 그 자리에서 카풀(K-03)을 시작할 때 쓴다. 내비 경로는 남긴다. 버렸으면 true.
     */
    fun discardUnshared(car: CarState? = lastCar): Boolean {
        val current = preview(car) ?: return false
        if (!current.sharedNothing) return false
        val keepDestination = destination
        val keepToll = routeTollWon
        reset()
        destination = keepDestination
        routeTollWon = keepToll
        return true
    }

    private fun waitingFromSetup(rider: Rider): Boolean {
        if (!(rider.aboard && rider.seat in manualSeats && rider.name != null && rider.name in setup.names)) return false
        // K-03에서 센서 없는 자리(3열 등)를 직접 골랐으면 그 자리에 탄 것으로 본다. 나중에 다른 자리에 누가 앉아도
        // 이 사람을 옮기지 않는다(옮기면 그 뒤 하차가 엉뚱한 사람에게 붙는다). 자리를 안 고른 예전 방식은 그대로.
        return !(setup.seatPlan != null && lastCar?.seated?.get(rider.seat) == null)
    }

    /** 출발 명단의 수동 탑승자를 센서가 감지한 [seat]로 옮긴다. 그런 사람이 없으면 false. */
    private fun seatWaitingRider(seat: Seat): Boolean {
        val current = ride ?: return false
        val waiting = current.riders.filter(::waitingFromSetup).minByOrNull { it.id } ?: return false
        manualSeats -= waiting.seat
        ride = current.copy(riders = current.riders.map { if (it.id == waiting.id) it.copy(seat = seat) else it })
        return true
    }

    private fun reset() {
        ride = null
        awaitingFirstDrive = false
        pending.clear()
        manualSeats.clear()
        forcedVacant.clear()
        routeTollWon = 0
        destination = null
        setup = TripSetup()
        segTollgates = 0
        segExtras = mutableListOf()
        mix.reset()
    }

    companion object {
        /** 이보다 오래된 저장본은 이어 가지 않고 그 자리에서 끝낸다(앱을 오래 꺼 둔 경우). */
        const val RESTORE_LIMIT_MS = 12 * 60 * 60_000L

        /** 자리 신호가 이만큼 바뀐 채로 있어야 타거나 내린 것으로 본다. */
        const val SEAT_STABLE_MS = 3_000L

        /** 같은 톨게이트를 이 안에 또 지나면 한 건으로 본다(기획서 8장 예외 처리). */
        const val TOLL_DEDUP_MS = 60_000L
    }
}

/** [RideTracker.snapshot]. 진행 중인 구간의 시작점과 수동으로 태운 자리까지 담는다. */
data class TrackerSnapshot(
    val ride: Ride,
    val segStartKm: Double,
    val kmKnown: Boolean,
    val segStartEnergy: Double?,
    val segTollgates: Int,
    val segExtras: List<Extra>,
    val nextRiderId: Int,
    val manualSeats: Set<Seat>,
    val forcedVacant: Set<Seat>,
    val routeTollWon: Int,
    val destination: String?,
    val setup: TripSetup,
    /** 저장한 시각. 오래된 저장본을 가려낸다. */
    val savedAt: Long = 0L,
    /** 저장할 때의 주행거리계. 오래된 저장본을 끝낼 때 마지막 구간의 끝으로 쓴다. */
    val lastKm: Double? = null,
    val awaitingFirstDrive: Boolean = false,
) {
    /** 시각과 주행거리를 뺀 내용이 같은지. 같으면 다시 저장하지 않아도 된다. */
    fun sameContent(other: TrackerSnapshot?): Boolean = other != null && plain() == other.plain()

    // 도심·고속 거리는 주행거리처럼 달리면 늘 바뀐다. 앱이 정한 주기마다 주행거리와 같이 저장하면 된다.
    private fun plain() = copy(savedAt = 0L, lastKm = null, ride = ride.copy(drivingMix = DrivingMix()))
}
