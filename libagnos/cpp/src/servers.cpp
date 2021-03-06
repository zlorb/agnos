//////////////////////////////////////////////////////////////////////////////
// Part of the Agnos RPC Framework
//    http://agnos.sourceforge.net
//
// Copyright 2011, International Business Machines Corp.
//                 Author: Tomer Filiba (tomerf@il.ibm.com)
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//    http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
//////////////////////////////////////////////////////////////////////////////

#include <stdio.h>
#include <cstdlib>
#include <string>
#include <algorithm>
#include "servers.hpp"


namespace agnos
{
	namespace servers
	{
		//////////////////////////////////////////////////////////////////////
		// BaseServer
		//////////////////////////////////////////////////////////////////////

		BaseServer::BaseServer(IProcessorFactory& processor_factory, shared_ptr<ITransportFactory> transport_factory) :
				processor_factory(processor_factory), transport_factory(transport_factory)
		{
		}

		void BaseServer::close()
		{
			transport_factory->close();
		}

		void BaseServer::serve()
		{
			while (true) {
				shared_ptr<ITransport> transport = transport_factory->accept();
				shared_ptr<BaseProcessor> proc = processor_factory.create(transport);
				serve_client(proc);
			}
		}

		//////////////////////////////////////////////////////////////////////
		// SimpleServer
		//////////////////////////////////////////////////////////////////////

		SimpleServer::SimpleServer(IProcessorFactory& processor_factory, shared_ptr<ITransportFactory> transport_factory) :
				BaseServer(processor_factory, transport_factory)
		{
		}

		void SimpleServer::serve_client(shared_ptr<BaseProcessor> proc)
		{
			proc->serve();
		}

		//////////////////////////////////////////////////////////////////////
		// ThreadedServer
		//////////////////////////////////////////////////////////////////////

		ThreadedServer::ThreadedServer(IProcessorFactory& processor_factory, shared_ptr<ITransportFactory> transport_factory) :
				BaseServer(processor_factory, transport_factory)
		{
		}

		static void _threadproc(shared_ptr<BaseProcessor> proc)
		{
			proc->serve();
		}

		void ThreadedServer::serve_client(shared_ptr<BaseProcessor> proc)
		{
			shared_ptr<boost::thread> thrd(new boost::thread(_threadproc, proc));
			threads.push_back(thrd);
		}

		//////////////////////////////////////////////////////////////////////
		// LibraryModeServer
		//////////////////////////////////////////////////////////////////////
		LibraryModeServer::LibraryModeServer(IProcessorFactory& processor_factory) :
				BaseServer(processor_factory,
					shared_ptr<SocketTransportFactory>(new SocketTransportFactory("127.0.0.1", 0)))
		{
		}

		LibraryModeServer::LibraryModeServer(IProcessorFactory& processor_factory, shared_ptr<SocketTransportFactory> transport_factory) :
				BaseServer(processor_factory, transport_factory)
		{
		}

		void LibraryModeServer::serve_client(shared_ptr<BaseProcessor> proc)
		{
			throw std::runtime_error("LibraryModeServer::serve_client not implemented");
		}

		void LibraryModeServer::serve()
		{
			shared_ptr<SocketTransportFactory> factory = boost::dynamic_pointer_cast<SocketTransportFactory>(transport_factory);
			std::cout << "AGNOS" << std::endl;
			std::cout << factory->acceptor->local_endpoint().address().to_string() << std::endl;
			std::cout << factory->acceptor->local_endpoint().port() << std::endl;
			std::cout.flush();
#ifdef _WIN32
			fclose(stdout);
#else
			::fclose(::stdout);
#endif

			shared_ptr<ITransport> transport = factory->accept();
			factory->close();

			shared_ptr<BaseProcessor> proc = processor_factory.create(transport);
			proc->serve();
		}


		//////////////////////////////////////////////////////////////////////
		// CmdlineServer
		//////////////////////////////////////////////////////////////////////

		enum ServingModes
		{
			MODE_SIMPLE,
			MODE_THREADED,
			MODE_LIB,
		};

		CmdlineServer::CmdlineServer(IProcessorFactory& processor_factory) :
				processor_factory(processor_factory)
		{
		}

		int CmdlineServer::main(int argc, const char* argv[])
		{
			ServingModes mode = MODE_SIMPLE;
			string host = "127.0.0.1";
			unsigned short port = 0;

			// start from 1, to skip arg[0]
			for (int i = 1; i < argc; i++) {
				string arg = argv[i];

				if (arg.compare("-m") == 0) {
					i += 1;
					if (i >= argc) {
						throw SwitchError("-h requires an argument");
					}
					arg = argv[i];
					std::transform(arg.begin(), arg.end(), arg.begin(), ::tolower);
					if (arg.compare("lib") == 0 || arg.compare("library") == 0) {
						mode = MODE_LIB;
					}
					else if (arg.compare("simple") == 0) {
						mode = MODE_SIMPLE;
					}
					else if (arg.compare("threaded") == 0) {
						mode = MODE_THREADED;
					}
					else {
						THROW_FORMATTED(SwitchError, "invalid server mode: " << arg);
					}
				}
				else if (arg.compare("-h") == 0) {
					i += 1;
					if (i >= argc) {
						throw SwitchError("-h requires an argument");
					}
					host = argv[i];
				}
				else if (arg.compare("-p") == 0) {
					i += 1;
					if (i >= argc) {
						throw SwitchError("-p requires an argument");
					}
					port = (unsigned short)::atoi(argv[i]);
				}
				else {
					THROW_FORMATTED(SwitchError, "invalid cmdline switch: " << arg);
				}
			}

			scoped_ptr<BaseServer> server;
			shared_ptr<SocketTransportFactory> transport_factory;

			switch (mode)
			{
			case MODE_SIMPLE:
				if (port == 0) {
					throw SwitchError("simple server requires specifying a port");
				}
				transport_factory.reset(new SocketTransportFactory(host.c_str(), port));
				server.reset(new SimpleServer(processor_factory, transport_factory));
				break;

			case MODE_THREADED:
				if (port == 0) {
					throw SwitchError("threaded server requires specifying a port");
				}
				transport_factory.reset(new SocketTransportFactory(host.c_str(), port));
				server.reset(new ThreadedServer(processor_factory, transport_factory));
				break;

			case MODE_LIB:
				transport_factory.reset(new SocketTransportFactory(host.c_str(), port));
				server.reset(new LibraryModeServer(processor_factory, transport_factory));
				break;

			default:
				THROW_FORMATTED(SwitchError, "invalid server mode" << mode);
			}

			server->serve();
			return 0;
		}

	}
}
