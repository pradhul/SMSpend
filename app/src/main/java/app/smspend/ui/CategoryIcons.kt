package app.smspend.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.DirectionsBike
import androidx.compose.material.icons.automirrored.outlined.ReceiptLong
import androidx.compose.material.icons.automirrored.outlined.TrendingUp
import androidx.compose.material.icons.outlined.AccountBalance
import androidx.compose.material.icons.outlined.AccountBalanceWallet
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.Category
import androidx.compose.material.icons.outlined.ChildCare
import androidx.compose.material.icons.outlined.Coffee
import androidx.compose.material.icons.outlined.DirectionsBus
import androidx.compose.material.icons.outlined.DirectionsCar
import androidx.compose.material.icons.outlined.FitnessCenter
import androidx.compose.material.icons.outlined.Flight
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.LocalGasStation
import androidx.compose.material.icons.outlined.LocalGroceryStore
import androidx.compose.material.icons.outlined.LocalHospital
import androidx.compose.material.icons.outlined.LocalPharmacy
import androidx.compose.material.icons.outlined.MedicalServices
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material.icons.outlined.Pets
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material.icons.outlined.Restaurant
import androidx.compose.material.icons.outlined.School
import androidx.compose.material.icons.outlined.ShoppingBag
import androidx.compose.material.icons.outlined.SportsEsports
import androidx.compose.material.icons.outlined.Storefront
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.material.icons.outlined.Train
import androidx.compose.material.icons.outlined.VolunteerActivism
import androidx.compose.material.icons.outlined.Wifi
import androidx.compose.material.icons.outlined.WorkOutline
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import app.smspend.domain.CategoryIconNames
import app.smspend.domain.SpendCategory
import app.smspend.util.runCatchingCancellable

object CategoryIcons {
    val Fallback: ImageVector = Icons.Outlined.Category

    private val byName: Map<String, ImageVector> = mapOf(
        "Category" to Icons.Outlined.Category,
        "Restaurant" to Icons.Outlined.Restaurant,
        "LocalGroceryStore" to Icons.Outlined.LocalGroceryStore,
        "ShoppingBag" to Icons.Outlined.ShoppingBag,
        "LocalGasStation" to Icons.Outlined.LocalGasStation,
        "DirectionsCar" to Icons.Outlined.DirectionsCar,
        "DirectionsBus" to Icons.Outlined.DirectionsBus,
        "DirectionsBike" to Icons.AutoMirrored.Outlined.DirectionsBike,
        "Flight" to Icons.Outlined.Flight,
        "Train" to Icons.Outlined.Train,
        "ReceiptLong" to Icons.AutoMirrored.Outlined.ReceiptLong,
        "AccountBalance" to Icons.Outlined.AccountBalance,
        "AccountBalanceWallet" to Icons.Outlined.AccountBalanceWallet,
        "TrendingUp" to Icons.AutoMirrored.Outlined.TrendingUp,
        "Movie" to Icons.Outlined.Movie,
        "SportsEsports" to Icons.Outlined.SportsEsports,
        "SwapHoriz" to Icons.Outlined.SwapHoriz,
        "Pets" to Icons.Outlined.Pets,
        "FitnessCenter" to Icons.Outlined.FitnessCenter,
        "MedicalServices" to Icons.Outlined.MedicalServices,
        "LocalHospital" to Icons.Outlined.LocalHospital,
        "LocalPharmacy" to Icons.Outlined.LocalPharmacy,
        "School" to Icons.Outlined.School,
        "Home" to Icons.Outlined.Home,
        "Coffee" to Icons.Outlined.Coffee,
        "PhoneAndroid" to Icons.Outlined.PhoneAndroid,
        "Wifi" to Icons.Outlined.Wifi,
        "WorkOutline" to Icons.Outlined.WorkOutline,
        "ChildCare" to Icons.Outlined.ChildCare,
        "VolunteerActivism" to Icons.Outlined.VolunteerActivism,
        "Storefront" to Icons.Outlined.Storefront,
        "Build" to Icons.Outlined.Build,
    )

    init {
        // Keep UI map aligned with Gemini allowlist.
        check(byName.keys.containsAll(CategoryIconNames.ALL)) {
            "CategoryIcons map missing names from CategoryIconNames"
        }
    }

    fun fromAllowlistName(name: String?): ImageVector {
        val normalized = CategoryIconNames.normalize(name) ?: return Fallback
        return byName[normalized] ?: Fallback
    }

    fun forKey(categoryKey: String, customIconName: String? = null): ImageVector =
        runCatchingCancellable {
            when {
                SpendCategory.isCustomKey(categoryKey) -> fromAllowlistName(customIconName)
                else -> forPreset(SpendCategory.fromStored(categoryKey))
            }
        }.getOrDefault(Fallback)

    fun forPreset(category: SpendCategory): ImageVector = when (category) {
        SpendCategory.FOOD_DINING -> Icons.Outlined.Restaurant
        SpendCategory.GROCERY -> Icons.Outlined.LocalGroceryStore
        SpendCategory.SHOPPING -> Icons.Outlined.ShoppingBag
        SpendCategory.FUEL -> Icons.Outlined.LocalGasStation
        SpendCategory.TRAVEL -> Icons.Outlined.DirectionsCar
        SpendCategory.BILLS -> Icons.AutoMirrored.Outlined.ReceiptLong
        SpendCategory.EMI -> Icons.Outlined.AccountBalance
        SpendCategory.SAVINGS -> Icons.Outlined.AccountBalanceWallet
        SpendCategory.ENTERTAINMENT -> Icons.Outlined.Movie
        SpendCategory.TRANSFERS -> Icons.Outlined.SwapHoriz
        SpendCategory.WITHDRAWALS -> Icons.Outlined.AccountBalanceWallet
        SpendCategory.SELF_TRANSFER -> Icons.Outlined.AccountBalance
        SpendCategory.CREDITS -> Icons.Outlined.AccountBalanceWallet
        SpendCategory.OTHER -> Fallback
    }
}

@Composable
fun categoryIcon(categoryKey: String, customIconName: String? = null): ImageVector =
    CategoryIcons.forKey(categoryKey, customIconName)
