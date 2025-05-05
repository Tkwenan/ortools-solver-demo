# Timetable Solver MRE (Minimal Reproducible Example)

This is a minimal reproducible example (MRE) of a timetable scheduling problem using Google OR-Tools. The code demonstrates a constraint programming approach to schedule lessons for multiple streams while respecting various constraints. I'm using Spring Boot 3.4.1 and ortools-java 9.11.4210.

## Problem Description

I'm using the CP-SAT solver to solve a scheduling/lesson assignment problem (part of a larger timetable-generation problem). The list of lessons is generated outside the solver and then passed as a parameter to the solver. I'm using the solver to specifically assign lessons to timeslots while observing certain constraints e.g. teacher shouldn't be scheduled for a lesson when marked as unavailable.

## Current Issue

When I run the solver, I get an OPTIMAL solution. However, upon closely examining the results produced by the solver, I noticed that it assigns multiple lessons to one timeslot on the same day for a given stream. This is a violation of a core constraint specified in the solver and should not happen even logically. I have two hard constraints in the solver file that should guard against this, but the solver seems to ignore these constraints. They're implemented in the methods: enforceLessonNonOverlapConstraints() and oneLessonPerTimeslotPerStreamPerDay() in the file MinimalTimetableSolver. I created this MRE to model and get to the bottom of this issue of multiple lessons at the same time on the same day for a given stream. There seems to be issue with the MRE and it gives me an INFEASIBLE solution despite me having the same exact code in the MRE as the one in my project. I also use the same exact test data, same version of or-tools etc. But it can still be used to view the constraint methods.

## Sample Output from Actual Project
Here is an example of the issue - different lesson ids and different subject ids (meaning completely different and unrealted lessons scheduled at the same time)
Day 1, Timeslot 2 | Lesson ID: 5105 | Stream ID: 67008 | Subject ID: 14 | Teacher IDs: [12519396] | Type: SINGLE
Day 1, Timeslot 2 | Lesson ID: 5172 | Stream ID: 67008 | Subject ID: 603 | Teacher IDs: [3658889, 12519396] | Type: SINGLE

To view more instances of this, you can log into https://testtimetable.zeraki.app/auth/login using the credentials: "username": "shaffyjuma@madibo", "password": "JNS2022" and view any of the timetables

## Questions for OR-Tools Discussion
1. What could be causing the multiple-lessons-in-same-timeslot issue in the actual project? Why is the solver ignoring hard constraints that prevent this from happening?


## Code Structure

- `MinimalTimetableSolver.java`: Main solver class implementing the constraint programming model
- `Lesson.java` and `LessonInfo.java`: Entity classes representing a lesson with its properties
- `Stream.java`: Entity class representing a stream/class
- `Schedule.java`: Entity class representing the timetable schedule with its timeslots

## Dependencies

- Google OR-Tools
- Java 21 
- Spring Boot (for the actual project)

## How to Run

1. Ensure OR-Tools is properly installed and configured
2. To compile and run the project, run the following commands:
   - sdk use java java21
   - mvn clean install -DskipTests
   - mvn spring-boot:run       
   

## Note

Although this MRE is a simplified version of a larger project, it's important to emphasize that the constraint logic is exactly the same
