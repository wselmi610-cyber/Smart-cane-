package com.smartcane.app.ui.reminder

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.smartcane.app.data.model.Reminder
import com.smartcane.app.databinding.ItemReminderBinding

class ReminderAdapter(
    private val onDelete: (Reminder) -> Unit
) : ListAdapter<Reminder, ReminderAdapter.ReminderViewHolder>(DiffCallback) {

    inner class ReminderViewHolder(
        private val binding: ItemReminderBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(reminder: Reminder) {
            binding.tvReminderTask.text = reminder.task

            // Format time
            val amPm = if (reminder.hour < 12) "AM" else "PM"
            val hour12 = when {
                reminder.hour == 0 -> 12
                reminder.hour > 12 -> reminder.hour - 12
                else -> reminder.hour
            }
            val min = if (reminder.minute < 10) "0${reminder.minute}"
            else "${reminder.minute}"
            binding.tvReminderTime.text = "$hour12:$min $amPm"

            binding.btnDeleteReminder.setOnClickListener {
                onDelete(reminder)
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ReminderViewHolder {
        val binding = ItemReminderBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return ReminderViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ReminderViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    companion object DiffCallback : DiffUtil.ItemCallback<Reminder>() {
        override fun areItemsTheSame(oldItem: Reminder, newItem: Reminder) =
            oldItem.id == newItem.id
        override fun areContentsTheSame(oldItem: Reminder, newItem: Reminder) =
            oldItem == newItem
    }
}
