package com.example.timetable_solver_demo;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.example.timetable_solver_demo.entities.Lesson;
import com.example.timetable_solver_demo.entities.Schedule;
import com.example.timetable_solver_demo.entities.ScheduleTimeslot;
import com.example.timetable_solver_demo.entities.SchoolTimetable;
import com.example.timetable_solver_demo.entities.Stream;
import com.example.timetable_solver_demo.entities.Teacher;
import com.example.timetable_solver_demo.repositories.LessonRepository;
import com.example.timetable_solver_demo.repositories.ScheduleRepository;
import com.example.timetable_solver_demo.repositories.SchoolTimetableRepository;

import java.util.List;
import java.util.stream.Collectors;

@Component
public class DataExportUtility {
    private final LessonRepository lessonRepo;
    private final ScheduleRepository scheduleRepo;
    private final SchoolTimetableRepository timetableRepository;

    public DataExportUtility(LessonRepository lessonRepo, ScheduleRepository scheduleRepo, SchoolTimetableRepository timetableRepository) {
        this.lessonRepo = lessonRepo;
        this.scheduleRepo = scheduleRepo;
        this.timetableRepository = timetableRepository;
    }

    @Transactional
    public void exportDataForMRE(Long scheduleId, Long timetableId) {
        if (scheduleId == null || timetableId == null) {
            throw new IllegalArgumentException("Schedule ID and Timetable ID cannot be null");
        }

        Schedule schedule = scheduleRepo.findById(scheduleId)
            .orElseThrow(() -> new IllegalArgumentException("Schedule not found with ID: " + scheduleId));
        
        SchoolTimetable timetable = timetableRepository.findById(timetableId)
            .orElseThrow(() -> new IllegalArgumentException("Timetable not found with ID: " + timetableId));

        List<Lesson> lessons = lessonRepo.fetchAllLessonsByTimetable(timetable);

        System.out.println("\n=== Lessons ===");
        lessons.forEach(lesson -> {
            if (lesson.getLessonInfo() == null) {
                System.out.println(String.format("Lesson ID: %d | ERROR: LessonInfo is null", lesson.getId()));
                return;
            }

            String subjectInfo = lesson.getLessonInfo().getSubject() != null ? 
                String.format("Subject ID: %d", lesson.getLessonInfo().getSubject().getId()) : 
                lesson.getLessonInfo().getCombinedSubject() != null ? 
                    String.format("Combined Subject ID: %d", lesson.getLessonInfo().getCombinedSubject().getId()) : 
                    "No subject assigned";

            System.out.println(String.format(
                "Lesson ID: %d | Stream ID: %d | %s | Type: %s | Teacher IDs: %s",
                lesson.getId(),
                lesson.getLessonInfo().getStream().getId(),
                subjectInfo,
                lesson.getLessonInfo().getLessonType(),
                lesson.getLessonInfo().getTeachers().stream()
                    .map(teacher -> String.valueOf(teacher.getId()))
                    .collect(Collectors.joining(", "))
            ));
        });

        // Get all streams involved
        List<Stream> streams = lessons.stream()
            .map(lesson -> lesson.getLessonInfo().getStream())
            .distinct()
            .collect(Collectors.toList());
        System.out.println("\n=== Streams ===");
        streams.forEach(stream -> 
            System.out.println(String.format("Stream ID: %d", stream.getId())));

        // Get all teachers involved
        List<Teacher> teachers = lessons.stream()
            .flatMap(lesson -> lesson.getLessonInfo().getTeachers().stream())
            .distinct()
            .collect(Collectors.toList());
        System.out.println("\n=== Teachers ===");
        teachers.forEach(teacher -> 
            System.out.println(String.format("Teacher ID: %d", teacher.getId())));

        // Get all timeslots
        List<ScheduleTimeslot> timeslots = schedule.getTimeslotList();
        System.out.println("\n=== Timeslots ===");
        timeslots.forEach(timeslot -> 
            System.out.println(String.format("Timeslot ID: %d | Activity: %s", 
                timeslot.getId(), timeslot.getTimeslotActivity())));
    }
} 
