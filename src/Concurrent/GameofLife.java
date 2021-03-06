package Concurrent;

import java.awt.*;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.stream.Collectors;

import javax.swing.JFrame;

import org.jocl.Pointer;
import org.jocl.Sizeof;
import org.jocl.cl_command_queue;
import org.jocl.cl_context;
import org.jocl.cl_context_properties;
import org.jocl.cl_device_id;
import org.jocl.cl_kernel;
import org.jocl.cl_mem;
import org.jocl.cl_platform_id;
import org.jocl.cl_program;

import static org.jocl.CL.CL_CONTEXT_PLATFORM;
import static org.jocl.CL.CL_DEVICE_TYPE_ALL;
import static org.jocl.CL.CL_MEM_READ_WRITE;
import static org.jocl.CL.CL_SUCCESS;
import static org.jocl.CL.CL_TRUE;
import static org.jocl.CL.clBuildProgram;
import static org.jocl.CL.clCreateBuffer;
import static org.jocl.CL.clCreateCommandQueue;
import static org.jocl.CL.clCreateContext;
import static org.jocl.CL.clCreateKernel;
import static org.jocl.CL.clCreateProgramWithSource;
import static org.jocl.CL.clEnqueueNDRangeKernel;
import static org.jocl.CL.clEnqueueReadBuffer;
import static org.jocl.CL.clEnqueueWriteBuffer;
import static org.jocl.CL.clGetDeviceIDs;
import static org.jocl.CL.clGetPlatformIDs;
import static org.jocl.CL.clSetKernelArg;

import java.applet.*;

public class GameofLife extends Applet {

	private LifeEnv env;
	private Worker worker;

	// Get the applet started
	public void init() {
		build(this);
		new Worker(this).start();
	}

	public void work() {
		while (true) {
			// Just sit in a loop running forever
			env.run1Generation();
		}
	}

	// Make a user interface
	private void build(Container f) {
		setLayout(new BorderLayout());
		env = new LifeEnv();
		env.setBackground(Color.decode("#AA7739"));
		f.add("Center", env);
	}
}

class LifeEnv extends Canvas {
	// This holds the data structures for the game and computes the currents
	// The update environment
	private int update[][];
	// The current env
	private int current[][];
	// Need to swap the envs over
	private int swap[][];

	// private static final variables are constants
	private static final int POINT_SIZE = 7;
	private static final Color POINT_COLOR = Color.decode("#FFD9AA");
	// Width and height of environment
	private static final int N = 100;
	private static final int CANVAS_SIZE = 800;

	private static int generCount = 0;

	public LifeEnv() {
		update = new int[N][N];
		current = new int[N][N];
		// square around the edges, initially chosen to see if the algorithm for
		// adding rows/cols was working
		for (int i = 0; i < N; i++) {
			current[0][i] = 1;
			current[99][i] = 1;
			current[i][99] = 1;
			current[i][0] = 1;
		}

		setSize(CANVAS_SIZE, CANVAS_SIZE);
	}

	public void run1Generation() {
		int scatterArray[][] = new int[102][102];
		int scatterArray2D[] = new int[10404];
		int sendArray[] = new int[10404];

		// flatten the array with 0's around the outside
		for (int i = 0; i < N; i++) {
			for (int j = 0; j < N; j++) {
				scatterArray[i + 1][j + 1] = current[i][j];
			}
		}
		int count = 0;
		for (int i = 0; i < N + 2; i++) {
			for (int j = 0; j < N + 2; j++) {
				scatterArray2D[count] = scatterArray[i][j];
				count++;
			}
		}

		// pointers
		final Pointer firstArrayPointer = Pointer.to(scatterArray2D);
		final Pointer secondArrayPointer = Pointer.to(sendArray);

		final int numOfPlatforms[] = new int[1];
		clGetPlatformIDs(0, null, numOfPlatforms);
		System.out.println("Platforms: " + numOfPlatforms[0]);

		// Grab the platforms
		final cl_platform_id platforms[] = new cl_platform_id[numOfPlatforms[0]];
		clGetPlatformIDs(numOfPlatforms[0], platforms, null);

		// Use the first platform
		final cl_platform_id platform = platforms[0];

		// Create the context properties
		final cl_context_properties contextProperties = new cl_context_properties();
		contextProperties.addProperty(CL_CONTEXT_PLATFORM, platform);

		// Number of devices
		int numOfDevices[] = new int[1];
		clGetDeviceIDs(platform, CL_DEVICE_TYPE_ALL, 0, null, numOfDevices);
		System.out.println("Devices: " + numOfDevices[0]);

		// Grab the devices
		final cl_device_id devices[] = new cl_device_id[numOfDevices[0]];
		clGetDeviceIDs(platform, CL_DEVICE_TYPE_ALL, numOfDevices[0], devices, null);

		// Use the first device
		final cl_device_id device = devices[0];

		// Create a context for the device
		final cl_context context = clCreateContext(contextProperties, 1, new cl_device_id[] { device }, null, null,
				null);

		// Create a command queue for the device
		final cl_command_queue commandQueue = clCreateCommandQueue(context, device, 0, null);

		// Read in the program source
		final String programSource = new BufferedReader(
				new InputStreamReader(main.class.getResourceAsStream("algorithm.cl"))).lines().parallel()
						.collect(Collectors.joining("\n"));

		// Create the program from the source code
		final cl_program program = clCreateProgramWithSource(context, 1, new String[] { programSource }, null, null);
		int buildErr = clBuildProgram(program, 0, null, null, null, null);
		if (buildErr != CL_SUCCESS) {
			System.out.println("build error");
		}

		// Create the kernels
		cl_kernel k_addSideRows = clCreateKernel(program, "addRows", null);
		cl_kernel k_addSideCols = clCreateKernel(program, "addCols", null);
		cl_kernel k_calculate = clCreateKernel(program, "calculate", null);

		// allocate memory
		int err[] = new int[1];
		cl_mem memObjects[] = new cl_mem[2];
		memObjects[0] = clCreateBuffer(context, CL_MEM_READ_WRITE, Sizeof.cl_int * (N + 2) * (N + 2), null, null); // firstArrayPointer
		memObjects[1] = clCreateBuffer(context, CL_MEM_READ_WRITE, Sizeof.cl_int * (N + 2) * (N + 2), null, null); // secondArrayPointer
		if (err[0] != CL_SUCCESS) {
			System.out.println("memObject error");
		}

		// write the arrays into the device memory
		clEnqueueWriteBuffer(commandQueue, memObjects[0], CL_TRUE, 0, Sizeof.cl_int * (N + 2) * (N + 2),
				firstArrayPointer, 0, null, null);
		clEnqueueWriteBuffer(commandQueue, memObjects[1], CL_TRUE, 0, Sizeof.cl_int * (N + 2) * (N + 2),
				secondArrayPointer, 0, null, null);

		// Kernel arguments
		clSetKernelArg(k_addSideRows, 0, Sizeof.cl_mem, Pointer.to(memObjects[0]));
		clSetKernelArg(k_addSideCols, 0, Sizeof.cl_mem, Pointer.to(memObjects[0]));
		clSetKernelArg(k_calculate, 0, Sizeof.cl_mem, Pointer.to(memObjects[0]));
		clSetKernelArg(k_calculate, 1, Sizeof.cl_mem, Pointer.to(memObjects[1]));

		// Work item dimensions
		final long sendSize[] = new long[] { 100 };
		final long localSize[] = new long[] { 1 };
		final long algSendSize[] = new long[] { 100, 100 }; // required
															// because
															// 2D
															// kernels
															// need
															// 2
															// arguments
		final long calc_local_work_size[] = new long[] { 1, 1 };

		int gener = 0;
		while (true) {

			// used to calculate the execution time. The time until now was
			// tested to be around 600ms on my machine.
			long sTime = System.nanoTime();

			// do the calculations on the cells
			clEnqueueNDRangeKernel(commandQueue, k_addSideRows, 1, null, sendSize, localSize, 0, null, null);
			clEnqueueNDRangeKernel(commandQueue, k_addSideCols, 1, null, sendSize, localSize, 0, null, null);
			clEnqueueNDRangeKernel(commandQueue, k_calculate, 2, null, algSendSize, calc_local_work_size, 0, null,
					null);

			if (gener % 2 == 1) {
				// reassign the arguments for the next iteration, swapping the
				// two arrays
				clSetKernelArg(k_addSideRows, 0, Sizeof.cl_mem, Pointer.to(memObjects[0]));
				clSetKernelArg(k_addSideCols, 0, Sizeof.cl_mem, Pointer.to(memObjects[0]));
				clSetKernelArg(k_calculate, 0, Sizeof.cl_mem, Pointer.to(memObjects[0]));
				clSetKernelArg(k_calculate, 1, Sizeof.cl_mem, Pointer.to(memObjects[1]));

				// read the buffer to the second array
				clEnqueueReadBuffer(commandQueue, memObjects[1], CL_TRUE, 0, Sizeof.cl_int * (N + 2) * (N + 2),
						secondArrayPointer, 0, null, null);

				// unflatten the array
				int newCount = 0;
				for (int i = 0; i < 102; i++) {
					for (int j = 0; j < 102; j++) {
						if (i == 0 || i == 101 || j == 0 || j == 101) {
							newCount++;
						} else {
							update[i - 1][j - 1] = sendArray[newCount];
							newCount++;
						}
					}
				}
				gener++;
				// print it out
				swap = current;
				current = update;
				update = swap;
				repaint();
			} else {
				// reassign the arguments for the next iteration, swapping the
				// two arrays
				clSetKernelArg(k_addSideRows, 0, Sizeof.cl_mem, Pointer.to(memObjects[1]));
				clSetKernelArg(k_addSideCols, 0, Sizeof.cl_mem, Pointer.to(memObjects[1]));
				clSetKernelArg(k_calculate, 0, Sizeof.cl_mem, Pointer.to(memObjects[1]));
				clSetKernelArg(k_calculate, 1, Sizeof.cl_mem, Pointer.to(memObjects[0]));

				clEnqueueReadBuffer(commandQueue, memObjects[0], CL_TRUE, 0, Sizeof.cl_int * (N + 2) * (N + 2),
						secondArrayPointer, 0, null, null);

				// make the array 2 dimensional the array
				int newCount = 0;
				for (int i = 0; i < 102; i++) {
					for (int j = 0; j < 102; j++) {
						if (i == 0 || i == 101 || j == 0 || j == 101) {
							newCount++;
						} else {
							update[i - 1][j - 1] = sendArray[newCount];
							newCount++;
						}
						// Slow things down so that you can see them
						long iters = 10000;
						do {
						} while (--iters > 0);
					}
				}
				long endTime = System.nanoTime();
				long duration = (endTime - sTime) / 1000000;// converting to
															// milli seconds
				System.out.println("Generation# " + generCount + " execution time = " + duration + "ms");

				gener++;
				swap = current;
				current = update;
				update = swap;
				repaint();
			}
			generCount++;
		}
	}

	// Draw the points that have value 1
	public void paint(Graphics g) {
		for (int i = 0; i < N; i++) {
			for (int j = 0; j < N; j++) {
				if (current[i][j] == 1) {
					drawPoint(i, j, 1, g);
				}
			}
		}

		g.setColor(Color.black);
		g.drawRect(0, 0, getWidth() - 1, getHeight() - 1);
	}

	private void drawPoint(int x, int y, int v, Graphics g) {
		Dimension d = (getSize());
		int mx = d.width * x / N;
		int my = d.height * y / N;
		if (v == 1) {
			g.setColor(POINT_COLOR);
		} else {
			g.setColor(getBackground());
		}
		g.fillOval(mx, my, POINT_SIZE, POINT_SIZE);
	}
}

class Worker extends Thread {

	private GameofLife game;

	public Worker(GameofLife g) {
		game = g;
	}

	public void run() {
		game.work();
	}

}

class main {

	public static void main(final String... args) {
		GameofLife g = new GameofLife();
		JFrame frame = new JFrame();
		frame.getContentPane().add(g);
		Container c = frame.getContentPane();
		Dimension d = new Dimension(800, 720);
		c.setPreferredSize(d);
		frame.pack();
		frame.setVisible(true);
		frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
		g.init();
	}
}
