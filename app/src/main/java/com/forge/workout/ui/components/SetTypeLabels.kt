package com.forge.workout.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.forge.workout.R
import com.forge.workout.model.SetType

@Composable fun setTypeLabel(type: SetType): String = stringResource(when(type) {
    SetType.WORKING -> R.string.set_type_working
    SetType.WARM_UP -> R.string.set_type_warm_up
    SetType.DROP_SET -> R.string.set_type_drop
    SetType.FAILURE -> R.string.set_type_failure
})
@Composable fun setNumberLabel(number: Int, type: SetType): String = when(type) {
    SetType.WORKING -> number.toString()
    SetType.WARM_UP -> stringResource(R.string.set_warm_up_number, number)
    SetType.DROP_SET -> stringResource(R.string.set_drop_number, number)
    SetType.FAILURE -> stringResource(R.string.set_failure_number, number)
}
