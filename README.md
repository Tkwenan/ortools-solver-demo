# Timetable Solver MRE (Minimal Reproducible Example)

This is a minimal reproducible example (MRE) of a timetable scheduling problem using Google OR-Tools. The code demonstrates a constraint programming approach to schedule lessons for multiple streams while respecting various constraints.

## Problem Description

The solver attempts to schedule lessons with the following characteristics:

- Lessons can be SINGLE (1 timeslot) or DOUBLE (2 consecutive timeslots)
- Lessons must be scheduled within valid CLASS timeslots
- No two lessons from the same stream can overlap
- Double lessons must be scheduled in consecutive timeslots
- Lessons should be distributed evenly across days

## Core Constraints

1. **Lesson Non-Overlap**: No two lessons from the same stream can be scheduled in the same timeslot
2. **Double Lesson Constraints**: Double lessons must be scheduled in consecutive timeslots
3. **Valid Slot Constraints**: Lessons can only be scheduled in valid CLASS timeslots
4. **Stream Constraints**: Each stream's lessons must be properly distributed

## Current Issue

The solver is failing to find a solution in the MRE, while a similar implementation in the actual project successfully finds a FEASIBLE solution. The main differences are:

1. The actual project uses a phased approach to constraint application
2. The actual project includes additional constraints for teacher availability and subject distribution
3. The actual project successfully schedules all lessons but may have multiple lessons in the same timeslot for a given stream

## Sample Output from Actual Project

[Include here a sample output from your actual project showing:

- The successful FEASIBLE solution
- The solver statistics
- An example of multiple lessons in the same timeslot]

## Questions for OR-Tools Discussion

1. Why does the MRE fail to find a solution while the actual project succeeds?
2. What could be causing the multiple-lessons-in-same-timeslot issue in the actual project?
3. Are there any best practices for handling phased constraint application in OR-Tools?

## Code Structure

- `MinimalTimetableSolver.java`: Main solver class implementing the constraint programming model
- `Lesson.java`: Entity class representing a lesson with its properties
- `Stream.java`: Entity class representing a stream/class
- `Schedule.java`: Entity class representing the timetable schedule

## Dependencies

- Google OR-Tools
- Java 8 or higher
- Spring Boot (for the actual project)

## How to Run

1. Ensure OR-Tools is properly installed and configured
2. Compile the project
3. Run the solver with appropriate input data

## Note

This MRE is a simplified version of a larger project. While the core constraint logic is the same, some features and optimizations from the actual project have been removed to focus on the specific issues being investigated.
