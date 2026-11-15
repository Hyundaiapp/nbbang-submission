// C-03 주행 현황 전체보기.
package com.nbbang.ui.driving

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import com.nbbang.ui.Look
import com.nbbang.ui.settle.SettleHeader
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.nbbang.RideViewModel
import com.nbbang.UiState
import com.nbbang.domain.Gear
import com.nbbang.ui.settle.SettleCard

@Composable
internal fun DrivingStatusScreen(state: UiState, vm: RideViewModel) {
    val ride = state.live
    val canEdit = state.car.gear == Gear.PARK && !state.car.moving && !state.restricted

    var boarding by remember(ride?.id, canEdit) { mutableStateOf(false) }
    var alighting by remember(ride?.id, canEdit) { mutableStateOf<Int?>(null) }
    var addingCost by remember(ride?.id, canEdit) { mutableStateOf(false) }

    BackHandler(onBack = vm::goHome)
    Column(Modifier.fillMaxSize().background(Look.colors.surface)) {
        SettleHeader("주행 현황", onBack = vm::goHome)
        Row(Modifier.weight(1f)) {
            SettleCard(Modifier.weight(1f).fillMaxHeight().padding(20.dp)) {
                DrivingStatusContent(
                    state,
                    onFinish = {
                        val current = vm.ui.value
                        if (ride != null && current.car.gear == Gear.PARK && !current.car.moving &&
                            !current.restricted && current.live?.id == ride.id
                        ) {
                            vm.endDrive()
                        }
                    },
                    onAddPassenger = { boarding = true },
                    onSelectPassenger = { alighting = it },
                    onAddCost = { addingCost = true },
                    showActions = true,
                    expanded = true,
                    actionsEnabled = canEdit,
                )
            }
        }
    }

    if (ride == null || !canEdit) return
    when {
        boarding -> DrivingStatusDialog(onClose = { boarding = false }) {
            BoardingPopup(state, onCancel = { boarding = false }) { boarders ->
                vm.confirmBoarding(boarders).also { if (it == null) boarding = false }
            }
        }
        alighting != null -> {
            val riderId = alighting ?: return
            DrivingStatusDialog(onClose = { alighting = null }) {
                AlightingPopup(state, riderId, onClose = { alighting = null }) { id ->
                    if (vm.ui.value.live?.id == ride.id) vm.alight(id)
                    alighting = null
                }
            }
        }
        addingCost -> DrivingStatusDialog(onClose = { addingCost = false }) {
            CostPopup(
                ride = ride,
                voiceInput = vm.costVoiceInput,
                onRequestVoice = { vm.requestCostVoice(ride.id) },
                onCancelVoice = { vm.cancelCostVoice(ride.id) },
                onClose = { addingCost = false },
                onAdd = { name, won ->
                    if (vm.ui.value.live?.id == ride.id) vm.addCost(null, name, won)
                    addingCost = false
                },
            )
        }
    }
}

@Composable
private fun DrivingStatusDialog(onClose: () -> Unit, content: @Composable () -> Unit) {
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.size(850.dp, 400.dp)) { content() }
    }
}
