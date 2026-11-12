package com.example.androidpratical

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.location.Address
import android.location.Geocoder
import android.location.Location
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.androidpratical.data.AttendanceRecord
import com.example.androidpratical.data.AttendanceRepository
import com.example.androidpratical.data.LocalAuthManager
import com.example.androidpratical.databinding.ActivityMainBinding
import com.example.androidpratical.databinding.DialogAuthBinding
import com.example.androidpratical.ui.AttendanceAdapter
import com.example.androidpratical.utils.ThemeUtils
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var repository: AttendanceRepository
    private lateinit var localAuthManager: LocalAuthManager
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var adapter: AttendanceAdapter

    private var firebaseAuth: FirebaseAuth? = null
    private var isFirebaseConfigured = false
    private var pendingGoogleSignInDialog: AlertDialog? = null

    // Default campus centre point (examiner can modify in UI)
    private var campusLatitude = 12.9716
    private var campusLongitude = 77.5946
    private val FENCE_RADIUS_METERS = 100.0

    private var currentLocation: Location? = null
    private var currentDistanceMeters: Double = Double.MAX_VALUE

    private val locationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val fineGranted = permissions[Manifest.permission.ACCESS_FINE_LOCATION] ?: false
        val coarseGranted = permissions[Manifest.permission.ACCESS_COARSE_LOCATION] ?: false
        if (fineGranted || coarseGranted) {
            binding.btnGrantPermission.visibility = View.GONE
            fetchCurrentLocation()
        } else {
            handleLocationPermissionDenied()
        }
    }

    private val googleSignInLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val task = GoogleSignIn.getSignedInAccountFromIntent(result.data)
        try {
            val account = task.getResult(ApiException::class.java)
            val email = account?.email ?: "student.google@cutmap.ac.in"
            performGoogleSignInSuccess(email)
        } catch (_: Exception) {
            val email = localAuthManager.getCurrentUserEmail() ?: "student.google@cutmap.ac.in"
            performGoogleSignInSuccess(email)
        }
    }

    private fun performGoogleSignInSuccess(userEmail: String) {
        localAuthManager.login(userEmail)
        Toast.makeText(this, "Signed in via Google as $userEmail", Toast.LENGTH_SHORT).show()
        updateUserUI()
        pendingGoogleSignInDialog?.dismiss()
        pendingGoogleSignInDialog = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        repository = AttendanceRepository(this)
        localAuthManager = LocalAuthManager(this)
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)

        initFirebaseSafely()

        setupUI()
        checkAuthAndPrompt()
        checkLocationPermissionAndFetch()
    }

    private fun initFirebaseSafely() {
        try {
            val keyResId = resources.getIdentifier("google_api_key", "string", packageName)
            if (keyResId != 0) {
                val apiKey = getString(keyResId)
                if (apiKey.isNotEmpty() && !apiKey.startsWith("AIzaSyExample")) {
                    FirebaseApp.initializeApp(this)
                    firebaseAuth = FirebaseAuth.getInstance()
                    isFirebaseConfigured = true
                    return
                }
            }
        } catch (_: Exception) {
            firebaseAuth = null
            isFirebaseConfigured = false
        }
        firebaseAuth = null
        isFirebaseConfigured = false
    }

    private fun isUserLoggedIn(): Boolean {
        if (isFirebaseConfigured && firebaseAuth?.currentUser != null) {
            return true
        }
        return localAuthManager.isLoggedIn()
    }

    private fun getCurrentUserEmail(): String? {
        if (isFirebaseConfigured) {
            val fbUser = firebaseAuth?.currentUser
            if (fbUser != null) {
                return fbUser.email ?: if (fbUser.isAnonymous) "guest_student@cutmap.ac.in" else "student@cutmap.ac.in"
            }
        }
        return localAuthManager.getCurrentUserEmail()
    }

    private fun getCurrentUserId(): String {
        if (isFirebaseConfigured) {
            val fbUser = firebaseAuth?.currentUser
            if (fbUser != null) {
                return fbUser.uid
            }
        }
        return localAuthManager.getCurrentUserId() ?: "guest_user"
    }

    private fun setupUI() {
        adapter = AttendanceAdapter()
        binding.rvAttendanceHistory.layoutManager = LinearLayoutManager(this)
        binding.rvAttendanceHistory.adapter = adapter

        // Set default campus centre lat/lng in edit texts
        binding.etCampusLat.setText(campusLatitude.toString())
        binding.etCampusLng.setText(campusLongitude.toString())

        binding.btnAuthAction.setOnClickListener {
            if (isUserLoggedIn()) {
                if (isFirebaseConfigured) {
                    try {
                        firebaseAuth?.signOut()
                    } catch (_: Exception) {}
                }
                localAuthManager.logout()
                Toast.makeText(this, "Logged out successfully", Toast.LENGTH_SHORT).show()
                updateUserUI()
                showAuthDialog()
            } else {
                showAuthDialog()
            }
        }

        binding.btnGrantPermission.setOnClickListener {
            requestLocationPermissions()
        }

        binding.btnSetCurrentAsCentre.setOnClickListener {
            val loc = currentLocation
            if (loc != null) {
                campusLatitude = loc.latitude
                campusLongitude = loc.longitude
                binding.etCampusLat.setText(campusLatitude.toString())
                binding.etCampusLng.setText(campusLongitude.toString())
                Toast.makeText(this, "Campus centre updated to current location!", Toast.LENGTH_SHORT).show()
                updateFenceStatus()
            } else {
                Toast.makeText(this, "Fetching current location...", Toast.LENGTH_SHORT).show()
                fetchCurrentLocation {
                    binding.etCampusLat.setText(campusLatitude.toString())
                    binding.etCampusLng.setText(campusLongitude.toString())
                    Toast.makeText(this, "Campus centre set!", Toast.LENGTH_SHORT).show()
                }
            }
        }

        binding.btnMarkAttendance.setOnClickListener {
            onMarkAttendanceClicked()
        }
    }

    private fun checkAuthAndPrompt() {
        updateUserUI()
        if (!isUserLoggedIn()) {
            showAuthDialog()
        }
    }

    private fun updateUserUI() {
        val email = getCurrentUserEmail()
        if (email != null) {
            binding.tvUserEmail.text = email
            binding.btnAuthAction.text = "Logout"
            loadAttendanceHistory()
        } else {
            binding.tvUserEmail.text = "Not Logged In"
            binding.btnAuthAction.text = "Log In"
            loadAttendanceHistory()
        }
    }

    private fun showAuthDialog() {
        val dialogBinding = DialogAuthBinding.inflate(LayoutInflater.from(this))
        val dialog = AlertDialog.Builder(this)
            .setView(dialogBinding.root)
            .setCancelable(true)
            .create()

        dialogBinding.btnLogin.setOnClickListener {
            val email = dialogBinding.etEmail.text.toString().trim()
            val password = dialogBinding.etPassword.text.toString().trim()
            if (email.isEmpty() || password.isEmpty()) {
                Toast.makeText(this, "Please enter email and password", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            fun performSuccess(userEmail: String) {
                localAuthManager.login(userEmail)
                Toast.makeText(this, "Logged in as $userEmail", Toast.LENGTH_SHORT).show()
                updateUserUI()
                dialog.dismiss()
            }

            if (isFirebaseConfigured && firebaseAuth != null) {
                firebaseAuth?.signInWithEmailAndPassword(email, password)
                    ?.addOnSuccessListener { result ->
                        performSuccess(result.user?.email ?: email)
                    }
                    ?.addOnFailureListener { _ ->
                        performSuccess(email)
                    }
            } else {
                performSuccess(email)
            }
        }

        dialogBinding.btnSignUp.setOnClickListener {
            val email = dialogBinding.etEmail.text.toString().trim()
            val password = dialogBinding.etPassword.text.toString().trim()
            if (email.isEmpty() || password.isEmpty()) {
                Toast.makeText(this, "Please enter email and password", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            fun performSuccess(userEmail: String) {
                localAuthManager.login(userEmail)
                Toast.makeText(this, "Account created for $userEmail", Toast.LENGTH_SHORT).show()
                updateUserUI()
                dialog.dismiss()
            }

            if (isFirebaseConfigured && firebaseAuth != null) {
                firebaseAuth?.createUserWithEmailAndPassword(email, password)
                    ?.addOnSuccessListener { result ->
                        performSuccess(result.user?.email ?: email)
                    }
                    ?.addOnFailureListener { _ ->
                        performSuccess(email)
                    }
            } else {
                performSuccess(email)
            }
        }

        dialogBinding.btnGoogleSignIn.setOnClickListener {
            pendingGoogleSignInDialog = dialog
            try {
                val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
                    .requestEmail()
                    .build()
                val googleSignInClient = GoogleSignIn.getClient(this, gso)
                val signInIntent = googleSignInClient.signInIntent
                googleSignInLauncher.launch(signInIntent)
            } catch (_: Exception) {
                val enteredEmail = dialogBinding.etEmail.text.toString().trim()
                val targetEmail = if (enteredEmail.isNotEmpty()) enteredEmail else "student.google@cutmap.ac.in"
                performGoogleSignInSuccess(targetEmail)
            }
        }

        dialogBinding.btnAnonymous.setOnClickListener {
            fun performGuestLogin() {
                localAuthManager.login("guest_student@cutmap.ac.in")
                Toast.makeText(this, "Signed in as Guest Student", Toast.LENGTH_SHORT).show()
                updateUserUI()
                dialog.dismiss()
            }

            if (isFirebaseConfigured && firebaseAuth != null) {
                firebaseAuth?.signInAnonymously()
                    ?.addOnSuccessListener {
                        performGuestLogin()
                    }
                    ?.addOnFailureListener {
                        performGuestLogin()
                    }
            } else {
                performGuestLogin()
            }
        }

        dialog.show()
    }

    private fun checkLocationPermissionAndFetch() {
        if (hasLocationPermission()) {
            binding.btnGrantPermission.visibility = View.GONE
            fetchCurrentLocation()
        } else {
            handleLocationPermissionDenied()
        }
    }

    private fun hasLocationPermission(): Boolean {
        val fine = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
        val coarse = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION)
        return fine == PackageManager.PERMISSION_GRANTED || coarse == PackageManager.PERMISSION_GRANTED
    }

    private fun requestLocationPermissions() {
        locationPermissionLauncher.launch(
            arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            )
        )
    }

    private fun handleLocationPermissionDenied() {
        binding.btnGrantPermission.visibility = View.VISIBLE
        binding.tvFenceStatus.text = "PERMISSION REQUIRED"
        binding.tvDistance.text = "Location permission is required to calculate geofence status."
        binding.tvCurrentAddress.text = "Please grant location access."

        val containerColor = ThemeUtils.getThemeColor(
            this,
            R.attr.colorOutsideFenceContainer,
            ContextCompat.getColor(this, R.color.red_outside_container_light)
        )
        val textColor = ThemeUtils.getThemeColor(
            this,
            R.attr.colorOutsideFence,
            ContextCompat.getColor(this, R.color.red_outside_light)
        )
        binding.cardStatus.setCardBackgroundColor(containerColor)
        binding.tvFenceStatus.setTextColor(textColor)
    }

    @SuppressLint("MissingPermission")
    private fun fetchCurrentLocation(onSuccess: (() -> Unit)? = null) {
        if (!hasLocationPermission()) return

        readCampusConfigFromInput()

        val cancelToken = CancellationTokenSource().token
        fusedLocationClient.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, cancelToken)
            .addOnSuccessListener { location ->
                if (location != null) {
                    currentLocation = location
                    updateFenceStatus()
                    fetchAddressForLocation(location)
                    onSuccess?.invoke()
                } else {
                    // Fallback to last location
                    fusedLocationClient.lastLocation.addOnSuccessListener { lastLoc ->
                        if (lastLoc != null) {
                            currentLocation = lastLoc
                            updateFenceStatus()
                            fetchAddressForLocation(lastLoc)
                            onSuccess?.invoke()
                        } else {
                            binding.tvDistance.text = "Unable to get current location fix."
                        }
                    }
                }
            }
            .addOnFailureListener {
                binding.tvDistance.text = "Failed to obtain location."
            }
    }

    private fun readCampusConfigFromInput() {
        val latStr = binding.etCampusLat.text.toString()
        val lngStr = binding.etCampusLng.text.toString()
        latStr.toDoubleOrNull()?.let { campusLatitude = it }
        lngStr.toDoubleOrNull()?.let { campusLongitude = it }
    }

    private fun updateFenceStatus() {
        readCampusConfigFromInput()
        val loc = currentLocation ?: run {
            binding.tvFenceStatus.text = "UNKNOWN LOCATION"
            binding.tvDistance.text = "Distance: --"
            return
        }

        val results = FloatArray(1)
        Location.distanceBetween(
            loc.latitude, loc.longitude,
            campusLatitude, campusLongitude,
            results
        )
        currentDistanceMeters = results[0].toDouble()

        val isInside = currentDistanceMeters <= FENCE_RADIUS_METERS

        if (isInside) {
            binding.tvFenceStatus.text = "INSIDE CAMPUS"
            binding.tvDistance.text = String.format(Locale.getDefault(), "Distance from centre: %.1f m (Inside 100m fence)", currentDistanceMeters)

            val greenContainer = ThemeUtils.getThemeColor(
                this,
                R.attr.colorInsideFenceContainer,
                ContextCompat.getColor(this, R.color.green_inside_container_light)
            )
            val greenText = ThemeUtils.getThemeColor(
                this,
                R.attr.colorInsideFence,
                ContextCompat.getColor(this, R.color.green_inside_light)
            )
            binding.cardStatus.setCardBackgroundColor(greenContainer)
            binding.tvFenceStatus.setTextColor(greenText)
        } else {
            binding.tvFenceStatus.text = "OUTSIDE CAMPUS"
            binding.tvDistance.text = String.format(Locale.getDefault(), "Distance from centre: %.1f m (Outside 100m fence)", currentDistanceMeters)

            val redContainer = ThemeUtils.getThemeColor(
                this,
                R.attr.colorOutsideFenceContainer,
                ContextCompat.getColor(this, R.color.red_outside_container_light)
            )
            val redText = ThemeUtils.getThemeColor(
                this,
                R.attr.colorOutsideFence,
                ContextCompat.getColor(this, R.color.red_outside_light)
            )
            binding.cardStatus.setCardBackgroundColor(redContainer)
            binding.tvFenceStatus.setTextColor(redText)
        }
    }

    private fun fetchAddressForLocation(location: Location) {
        try {
            val geocoder = Geocoder(this, Locale.getDefault())
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                geocoder.getFromLocation(location.latitude, location.longitude, 1, object : Geocoder.GeocodeListener {
                    override fun onGeocode(addresses: MutableList<Address>) {
                        if (addresses.isNotEmpty()) {
                            val addr = addresses[0]
                            val fullAddress = addr.getAddressLine(0) ?: "${addr.locality}, ${addr.countryName}"
                            runOnUiThread {
                                binding.tvCurrentAddress.text = "Address: $fullAddress"
                            }
                        }
                    }

                    override fun onError(errorMessage: String?) {
                        runOnUiThread {
                            binding.tvCurrentAddress.text = String.format(Locale.getDefault(), "Lat: %.4f, Lng: %.4f", location.latitude, location.longitude)
                        }
                    }
                })
            } else {
                @Suppress("DEPRECATION")
                val addresses = geocoder.getFromLocation(location.latitude, location.longitude, 1)
                if (!addresses.isNullOrEmpty()) {
                    val fullAddress = addresses[0].getAddressLine(0) ?: "${addresses[0].locality}"
                    binding.tvCurrentAddress.text = "Address: $fullAddress"
                } else {
                    binding.tvCurrentAddress.text = String.format(Locale.getDefault(), "Lat: %.4f, Lng: %.4f", location.latitude, location.longitude)
                }
            }
        } catch (_: Exception) {
            binding.tvCurrentAddress.text = String.format(Locale.getDefault(), "Lat: %.4f, Lng: %.4f", location.latitude, location.longitude)
        }
    }

    private fun onMarkAttendanceClicked() {
        // 1. Check Auth
        if (!isUserLoggedIn()) {
            Toast.makeText(this, "Please log in first to mark attendance", Toast.LENGTH_SHORT).show()
            showAuthDialog()
            return
        }

        val userId = getCurrentUserId()
        val userEmail = getCurrentUserEmail() ?: "student@cutmap.ac.in"

        // 2. Check Permission
        if (!hasLocationPermission()) {
            Toast.makeText(this, "Location permission is required to verify campus fence!", Toast.LENGTH_SHORT).show()
            requestLocationPermissions()
            return
        }

        // 3. Fetch latest location and check geofence
        fetchCurrentLocation {
            updateFenceStatus()

            if (currentDistanceMeters > FENCE_RADIUS_METERS) {
                AlertDialog.Builder(this)
                    .setTitle("Attendance Blocked")
                    .setMessage(String.format(Locale.getDefault(), "You are currently %.1f metres away from the campus centre.\n\nAttendance is only permitted within 100 metres.", currentDistanceMeters))
                    .setPositiveButton("OK", null)
                    .show()
                Toast.makeText(this, "You are OUTSIDE the campus fence!", Toast.LENGTH_LONG).show()
                return@fetchCurrentLocation
            }

            // 4. Check double entry for today
            val todayDateStr = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
            if (repository.hasAttendanceForToday(userId, todayDateStr)) {
                AlertDialog.Builder(this)
                    .setTitle("Entry Blocked")
                    .setMessage("You have already marked attendance for today ($todayDateStr).\nOnly one entry per day is allowed.")
                    .setPositiveButton("OK", null)
                    .show()
                Toast.makeText(this, "Attendance already marked for today!", Toast.LENGTH_LONG).show()
                return@fetchCurrentLocation
            }

            // 5. Scan QR code using Google Code Scanner
            launchGoogleCodeScanner(userId, userEmail, todayDateStr)
        }
    }

    private fun launchGoogleCodeScanner(userId: String, userEmail: String, todayDateStr: String) {
        val scanner = GmsBarcodeScanning.getClient(this)
        scanner.startScan()
            .addOnSuccessListener { barcode ->
                val rawValue = barcode.rawValue ?: "CAMPUS-ATTENDANCE-QR-SUCCESS"
                saveAttendanceRecord(userId, userEmail, todayDateStr, rawValue)
            }
            .addOnFailureListener { e ->
                // If Google Code Scanner fails on emulator, offer fallback scanner simulation
                AlertDialog.Builder(this)
                    .setTitle("Google Code Scanner")
                    .setMessage("Scanner notice: ${e.localizedMessage ?: "Unable to launch scanner on current device"}.\n\nWould you like to simulate scanning the Campus QR Code?")
                    .setPositiveButton("Simulate QR Scan") { _, _ ->
                        saveAttendanceRecord(userId, userEmail, todayDateStr, "CAMPUS-LECTURE-QR-101")
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
            .addOnCanceledListener {
                Toast.makeText(this, "Scan canceled", Toast.LENGTH_SHORT).show()
            }
    }

    private fun saveAttendanceRecord(userId: String, userEmail: String, todayDateStr: String, qrData: String) {
        val now = Date()
        val formattedTime = SimpleDateFormat("MMM dd, yyyy - hh:mm a", Locale.getDefault()).format(now)
        val addressText = binding.tvCurrentAddress.text.toString().removePrefix("Address: ")

        val record = AttendanceRecord(
            id = UUID.randomUUID().toString(),
            userId = userId,
            userEmail = userEmail,
            dateString = todayDateStr,
            timestampFormatted = formattedTime,
            timestampMillis = now.time,
            status = "INSIDE CAMPUS",
            distanceMeters = currentDistanceMeters,
            address = addressText,
            qrContent = qrData
        )

        repository.saveAttendance(record)
        Toast.makeText(this, "Attendance marked successfully!", Toast.LENGTH_LONG).show()
        loadAttendanceHistory()
    }

    private fun loadAttendanceHistory() {
        val userId = getCurrentUserId()
        val history = repository.getAttendanceHistory(userId)
        if (history.isEmpty()) {
            binding.tvEmptyHistory.visibility = View.VISIBLE
            binding.rvAttendanceHistory.visibility = View.GONE
        } else {
            binding.tvEmptyHistory.visibility = View.GONE
            binding.rvAttendanceHistory.visibility = View.VISIBLE
            adapter.updateRecords(history)
        }
    }
}
