package com.example.androidpratical.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.example.androidpratical.R
import com.example.androidpratical.data.AttendanceRecord
import com.example.androidpratical.databinding.ItemAttendanceBinding
import java.util.Locale

class AttendanceAdapter(
    private var records: List<AttendanceRecord> = emptyList(),
) : RecyclerView.Adapter<AttendanceAdapter.ViewHolder>() {

    fun updateRecords(newRecords: List<AttendanceRecord>) {
        records = newRecords
        notifyDataSetChanged()
    }

    class ViewHolder(val binding: ItemAttendanceBinding) :
        RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemAttendanceBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val record = records[position]
        with(holder.binding) {
            tvRecordDate.text = record.timestampFormatted
            tvRecordDistance.text = String.format(Locale.getDefault(), "Distance: %.1f metres", record.distanceMeters)
            tvRecordAddress.text = String.format(Locale.getDefault(), "Address: %s", record.address)
            tvRecordQr.text = String.format(Locale.getDefault(), "QR Data: %s", record.qrContent)
            chipStatus.text = record.status

            val isInside = record.distanceMeters <= 100.0
            val colorRes = if (isInside) R.color.green_inside_light else R.color.red_outside_light
            chipStatus.setChipBackgroundColorResource(colorRes)
        }
    }

    override fun getItemCount(): Int = records.size
}
